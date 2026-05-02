package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ExpandedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询扩展服务
 * 将用户问题改写为多个不同表述，提高检索召回率
 *
 * LLM 调用使用 call() 同步模式，确保 JSON 输出干净可解析；
 * LLM 失败时回退到原始查询，不做任何变体生成。
 */
@Service
public class QueryExpander {

    private static final Logger logger = LoggerFactory.getLogger(QueryExpander.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*\\]", Pattern.DOTALL);

    @Autowired
    private ChatService chatService;

    @Value("${rag.model:qwen-plus}")
    private String modelName;

    /**
     * 扩展查询
     */
    public ExpandedQuery expand(String originalQuery) {
        ExpandedQuery result = new ExpandedQuery(originalQuery);

        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return result;
        }

        String trimmed = originalQuery.trim();
        List<String> llmQueries = tryLLMExpand(trimmed);

        if (llmQueries != null && !llmQueries.isEmpty()) {
            for (String q : llmQueries) {
                if (!q.equals(trimmed) && !result.getExpandedQueries().contains(q)) {
                    result.addExpandedQuery(q);
                }
            }
            logger.info("LLM 查询扩展: '{}' -> {} 个变体: {}", trimmed, result.size(), result.getAllQueries());
        } else {
            logger.info("查询扩展（未改写）: '{}'", trimmed);
        }

        return result;
    }

    private List<String> tryLLMExpand(String query) {
        try {
            String systemPrompt = """
                    你是一个查询扩展专家。将用户查询改写为 2 个不同表述以提高检索召回率。

                    改写策略：
                    1. 同义替换：用不同措辞表达相同意思
                    2. 关键词扩展：加入相关技术关键词

                    严格只输出JSON数组，不要包含任何其他文字、markdown标记或解释：
                    ["改写1","改写2"]
                    """;

            String prompt = "原始查询: " + query;
            String rawResult = callDashScope(chatService.getDashScopeApiKey(), systemPrompt, prompt, 0.3f);

            if (rawResult == null || rawResult.isEmpty()) {
                logger.warn("LLM 查询扩展返回空: '{}'", query);
                return null;
            }

            List<String> queries = parseLLMResult(rawResult);
            if (queries.isEmpty()) {
                logger.warn("LLM 查询扩展解析失败，原始返回: '{}'", rawResult);
                return null;
            }

            return queries;
        } catch (Exception e) {
            logger.error("LLM 查询扩展异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用 call() 同步调用 DashScope
     * 查询扩展需要干净的 JSON 输出，不适合用 streamCall 增量模式
     */
    private String callDashScope(String apiKey, String systemPrompt, String userPrompt, float temperature) {
        try {
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Message.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(systemPrompt)
                        .build());
            }
            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content(userPrompt)
                    .build());

            Generation generation = new Generation();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(modelName)
                    .messages(messages)
                    .temperature(temperature)
                    .resultFormat("message")
                    .build();

            GenerationResult generationResult = generation.call(param);

            if (generationResult == null || generationResult.getOutput() == null) {
                logger.warn("DashScope call 返回空: {}", userPrompt);
                return null;
            }

            GenerationOutput output = generationResult.getOutput();
            if (output.getChoices() == null || output.getChoices().isEmpty()) {
                logger.warn("DashScope call 返回空 choices: {}", userPrompt);
                return null;
            }

            String content = output.getChoices().get(0).getMessage().getContent();
            if (content == null || content.isEmpty()) {
                logger.warn("DashScope call 返回空内容: {}", userPrompt);
                return null;
            }

            return content;

        } catch (NoApiKeyException | InputRequiredException e) {
            logger.error("DashScope call 异常: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("DashScope call 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组
     */
    private List<String> parseLLMResult(String json) {
        List<String> queries = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return queries;
        }

        try {
            String jsonStr = extractJson(json);
            JsonNode root = MAPPER.readTree(jsonStr);
            if (!root.isArray()) {
                logger.warn("JSON 根节点不是数组: {}", jsonStr);
                return queries;
            }
            for (JsonNode node : root) {
                String query = node.asText().trim();
                if (!query.isEmpty() && queries.size() < 3) {
                    queries.add(query);
                }
            }
        } catch (Exception e) {
            logger.warn("解析 LLM 查询扩展结果失败: {}", e.getMessage());
        }

        return queries;
    }

    /**
     * 从文本中提取 JSON 数组字符串
     */
    private String extractJson(String text) {
        String cleaned = text.trim();

        // 处理 markdown 代码块
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }

        // 尝试提取 JSON 数组
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            return matcher.group();
        }

        return cleaned;
    }
}
