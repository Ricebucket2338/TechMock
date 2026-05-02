package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 意图识别服务
 * 三层规则 + LLM 终裁：
 *   Layer 0 - 明确意图（面试）→ 直接返回
 *   Layer 1 - 技术关键词命中 → TECH_QUESTION → 走 RAG
 *   Layer 2 - LLM 意图分类 → 返回什么就是什么
 *   Layer 3 - 安全兜底 → CHITCHAT（基础设施异常时保护性降级）
 */
@Service
public class IntentClassifier {

    private static final Logger logger = LoggerFactory.getLogger(IntentClassifier.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 技术词表 ====================

    private static final Set<String> TECH_KEYWORDS = Set.of(
            "tcp", "udp", "http", "https", "ftp", "ssh", "ssl", "tls", "dns",
            "websocket", "rpc", "rest", "graphql", "grpc", "soap",
            "jvm", "jdk", "jre", "gc", "spring", "bean", "aop", "ioc", "maven",
            "gradle", "mybatis", "hibernate", "netty", "jpa", "servlet",
            "mysql", "redis", "mongodb", "postgresql", "sql", "nosql",
            "elasticsearch", "milvus", "faiss",
            "kafka", "rabbitmq", "rocketmq", "zookeeper", "dubbo",
            "nginx", "docker", "kubernetes", "k8s",
            "html", "css", "javascript", "vue", "react", "angular", "json", "yaml", "xml",
            "api", "sdk", "linux", "git", "a2a", "agent", "mq"
    );

    private static final Pattern CHINESE_TECH_PATTERN = Pattern.compile(
            ".*(向量|并发|线程|内存|垃圾回收|索引|缓存|代理|容器|微服务|分布式|负载均衡|序列化|反序列化|死锁|反射|多态|继承|封装).*");

    // ==================== 规则模式 ====================

    private static final Pattern INTERVIEW_START_PATTERN = Pattern.compile(
            ".*(面试|开始面试|模拟面试|来一场面试|面试助手|面试官|我想面试|我要面试).*");

    // ==================== 依赖注入 ====================

    @Autowired
    private ChatService chatService;

    @Value("${rag.model:qwen-plus}")
    private String modelName;

    /**
     * 识别用户意图
     */
    public IntentResult classify(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return new IntentResult(IntentResult.IntentType.TECH_QUESTION, 0.3);
        }

        String input = userInput.trim();

        // Layer 0: 明确意图（面试）
        IntentResult explicit = checkExplicitIntent(input);
        if (explicit != null) {
            return explicit;
        }

        // Layer 1: 技术关键词命中 → TECH_QUESTION
        if (containsTechKeyword(input)) {
            return new IntentResult(IntentResult.IntentType.TECH_QUESTION, 0.80);
        }

        // Layer 2: LLM 意图分类（终裁）
        IntentResult llmResult = classifyByLLM(input);
        if (llmResult != null) {
            return llmResult;
        }

        // Layer 3: 安全兜底（LLM 调用异常时触发）
        return new IntentResult(IntentResult.IntentType.CHITCHAT, 0.50);
    }

    private IntentResult checkExplicitIntent(String input) {
        if (INTERVIEW_START_PATTERN.matcher(input).find()) {
            return new IntentResult(IntentResult.IntentType.INTERVIEW_START, 0.95);
        }
        return null;
    }

    private boolean containsTechKeyword(String input) {
        String lower = input.toLowerCase();
        for (String keyword : TECH_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return CHINESE_TECH_PATTERN.matcher(input).find();
    }

    // ==================== LLM 意图分类 ====================

    private IntentResult classifyByLLM(String input) {
        try {
            String systemPrompt = """
                    你是一个意图分类器。判断用户输入属于以下类别之一：
                    - chitchat: 闲聊/问候/情绪表达/脏话/语气词/敷衍回复（如"你好"、"谢谢"、"哈哈哈"、"滚"、"额"）
                    - tech_question: 技术知识问答（如"HashMap底层是什么？"、"Redis怎么使用"、"怎么连不上数据库"）
                    - clarify: 追问/澄清（如"能详细解释一下吗？"、"继续说"）
                    - interview_start: 开始面试（如"我想开始一场面试"）

                    判断规则：
                    - 纯数字、语气词重复、纯符号 → chitchat
                    - 包含技术名词或问题表述 → tech_question
                    - 不确定时优先归类为 chitchat

                    严格只输出JSON，不要包含任何其他文字、markdown标记或解释：
                    {"type":"类别名称","confidence":0.0到1.0的数字}
                    """;

            String result = callDashScope(chatService.getDashScopeApiKey(), systemPrompt, "用户输入: " + input, 0.1f);
            if (result != null && !result.isEmpty()) {
                IntentResult parsed = parseLLMResult(result);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            logger.warn("LLM 意图分类失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 使用 call() 同步调用 DashScope
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
                return null;
            }

            GenerationOutput output = generationResult.getOutput();
            if (output.getChoices() == null || output.getChoices().isEmpty()) {
                return null;
            }

            String content = output.getChoices().get(0).getMessage().getContent();
            if (content == null || content.isEmpty()) {
                return null;
            }

            return content;
        } catch (Exception e) {
            logger.error("DashScope call 异常: {}", e.getMessage());
            return null;
        }
    }

    private IntentResult parseLLMResult(String json) {
        try {
            String jsonStr = extractJson(json);
            JsonNode root = MAPPER.readTree(jsonStr);

            IntentResult.IntentType intentType = IntentResult.IntentType.CHITCHAT;
            double confidence = 0.70;

            if (root.has("type")) {
                String type = root.get("type").asText();
                switch (type.toLowerCase()) {
                    case "tech_question" -> intentType = IntentResult.IntentType.TECH_QUESTION;
                    case "clarify" -> intentType = IntentResult.IntentType.CLARIFY;
                    case "chitchat" -> intentType = IntentResult.IntentType.CHITCHAT;
                    case "interview_start" -> intentType = IntentResult.IntentType.INTERVIEW_START;
                }
            }

            if (root.has("confidence")) {
                confidence = root.get("confidence").asDouble(0.70);
            }

            return new IntentResult(intentType, confidence);
        } catch (Exception e) {
            logger.warn("解析 LLM 意图分类结果失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String text) {
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }
}
