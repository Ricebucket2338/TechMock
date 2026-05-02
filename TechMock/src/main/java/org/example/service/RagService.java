package org.example.service;

import org.example.dto.ExpandedQuery;
import org.example.dto.IntentResult;
import org.example.service.HybridSearchService.HybridSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 增强版管道：意图识别 → 查询扩展 → 混合检索 → 重排 → 生成
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private IntentClassifier intentClassifier;

    @Autowired
    private QueryExpander queryExpander;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private ReRankService reRankService;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.model:qwen-plus}")
    private String model;

    /**
     * 最大上下文 token 数（qwen-plus 支持 32k，上下文占约一半）
     */
    private static final int MAX_CONTEXT_TOKENS = 8000;

    /**
     * 估算字符数到 token 的粗略比例（中文约 1.5 字符/token）
     */
    private static final double CHARS_PER_TOKEN = 1.5;

    /**
     * 流式处理用户问题（不带历史消息）
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 阻塞式处理用户问题（不带历史消息）
     * 等待流式生成完成后返回完整答案
     */
    public String query(String question) {
        return query(question, new ArrayList<>());
    }

    /**
     * 阻塞式处理用户问题（带历史消息）
     * 等待流式生成完成后返回完整答案
     */
    public String query(String question, List<Map<String, String>> history) {
        final String[] result = new String[1];
        final Exception[] error = new Exception[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        queryStream(question, history, new StreamCallback() {
            @Override
            public void onSearchResults(List<HybridSearchService.HybridSearchResult> results) {
                // ignored
            }

            @Override
            public void onReasoningChunk(String chunk) {
                // ignored
            }

            @Override
            public void onContentChunk(String chunk) {
                if (result[0] == null) {
                    result[0] = "";
                }
                result[0] += chunk;
            }

            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                result[0] = fullContent;
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                error[0] = e;
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询被中断", e);
        }

        if (error[0] != null) {
            throw new RuntimeException(error[0]);
        }

        return result[0] != null ? result[0] : "";
    }

    /**
     * 流式处理用户问题（带历史消息）
     * 增强管道：意图识别 → 查询扩展 → 混合检索 → 重排 → 生成
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);

            // 1. 意图识别
            IntentResult intent = intentClassifier.classify(question);
            logger.info("意图识别结果: {}, confidence: {}", intent.getType(), intent.getConfidence());

            // 闲聊直接回复，不走检索
            if (intent.isChitchat()) {
                handleChitchat(question, history, callback);
                return;
            }

            // 2. 查询扩展
            ExpandedQuery expanded = queryExpander.expand(question);
            logger.info("查询扩展: {} -> {} 个变体", question, expanded.size());

            // 3. 混合检索
            List<String> queries = expanded.getAllQueries();
            List<HybridSearchResult> candidates = hybridSearchService.hybridSearch(queries, topK * 4);

            if (candidates.isEmpty()) {
                logger.warn("检索未返回结果，降级为直接生成回复");
                String fallbackPrompt = buildPrompt(question, "", 0);
                generateAnswerStream(fallbackPrompt, history, callback);
                return;
            }

            // 4. 重排
            List<HybridSearchResult> topResults = reRankService.rerank(candidates);

            // 发送检索结果通知
            callback.onSearchResults(topResults);

            // 5. 构建上下文和提示词
            String context = buildContext(topResults);
            String prompt = buildPrompt(question, context, topResults.size());

            // 6. 流式生成
            generateAnswerStream(prompt, history, callback);

        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    /**
     * 处理闲聊类意图
     */
    private void handleChitchat(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            String prompt = "你是一个友好的AI面试助手。用户说: " + question + "\n请简短、友好地回应。";
            generateAnswerStream(prompt, history, callback);
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    /**
     * 构建混合检索结果的上下文（带窗口截断）
     */
    private String buildContext(List<HybridSearchResult> searchResults) {
        return buildContextInternal(
                searchResults.size(),
                (idx) -> searchResults.get(idx).getContent());
    }

    /**
     * 通用上下文构建：按 token 限制截断，避免超出模型上下文窗口
     */
    @FunctionalInterface
    private interface ContentProvider {
        String getContent(int index);
    }

    private String buildContextInternal(int count, ContentProvider provider) {
        int maxChars = (int) (MAX_CONTEXT_TOKENS * CHARS_PER_TOKEN);
        StringBuilder context = new StringBuilder();
        int truncatedCount = 0;
        for (int i = 0; i < count; i++) {
            String header = "【参考资料 " + (i + 1) + "】\n";
            String content = provider.getContent(i);
            int entrySize = header.length() + content.length() + 2; // +2 for \n\n

            if (context.length() + entrySize > maxChars && context.length() > 0) {
                truncatedCount = count - i;
                break;
            }

            context.append(header);
            if (context.length() + content.length() > maxChars) {
                // 单条过长：截断到剩余空间
                int remaining = maxChars - context.length() - header.length() - 4;
                if (remaining > 100) {
                    context.append(content, 0, remaining);
                    context.append("...(内容过长已截断)");
                }
                truncatedCount = count - i - 1;
                break;
            }
            context.append(content).append("\n\n");
        }
        if (truncatedCount > 0) {
            context.append("（注：共检索到 ").append(count).append(" 条资料，因篇幅限制仅展示 ")
                    .append(count - truncatedCount).append(" 条）\n");
        }
        return context.toString();
    }

    /**
     * 构建提示词（动态策略：根据参考资料数量调整回答策略）
     */
    private String buildPrompt(String question, String context, int docCount) {
        if (docCount >= 3) {
            // 资料充足：参考资料 + 模型知识，不暴露检索过程
            return String.format(
                    "你是一个专业的技术助手，请用自然流畅的中文回答用户问题。\n\n" +
                    "以下背景信息可能对你有用：\n%s\n" +
                    "用户问题：%s\n\n" +
                    "回答要求：\n" +
                    "1. 严格判断给你提供的信息是否有用，参考信息只是作为辅助信息，不要求一定要用到参考信息，如果你认为不相关则根据自己的知识回答\n" +
                    "2. 如果背景信息不足以完整回答，可以补充你自己的知识\n" +
                    "3. 不要在回答中提及\"参考资料\"、\"背景信息\"、\"检索\"、\"召回\"等词\n" +
                    "4. 不要逐条列出背景信息内容，而是将其融入自然的回答中\n" +
                    "5. 如果背景信息与你的知识不一致，以背景信息为准\n"+
                    "6. 不要让用户感知到给你提供了额外信息这个事件",
                    context, question);
        } else if (docCount >= 1) {
            // 资料有限：模型知识为主，参考资料做补充
            return String.format(
                    "你是一个专业的技术助手，请用自然流畅的中文回答用户问题。\n\n" +
                    "以下背景信息仅供参考：\n%s\n" +
                    "用户问题：%s\n\n" +
                    "回答要求：\n" +
                    "1. 如果背景信息中有用信息，可以引用其中的具体细节\n" +
                    "2. 主要使用你自己的知识来回答\n" +
                    "3. 不要在回答中提及\"参考资料\"、\"背景信息\"、\"检索\"、\"召回\"等词\n" +
                    "4. 不要逐条列出背景信息内容，而是将其融入自然的回答中",
                    context, question);
        } else {
            // 无参考资料：完全自由回答
            return String.format(
                    "你是一个专业的技术助手，请用自然流畅的中文回答用户问题。\n\n" +
                    "用户问题：%s",
                    question);
        }
    }

    /**
     * 生成答案（流式）
     */
    private void generateAnswerStream(String prompt, List<Map<String, String>> history, StreamCallback callback) {
        try {
            // 构建消息列表
            List<com.alibaba.dashscope.common.Message> messages = new ArrayList<>();
            for (Map<String, String> historyMsg : history) {
                String role = historyMsg.get("role");
                String content = historyMsg.get("content");
                messages.add(com.alibaba.dashscope.common.Message.builder()
                        .role(role)
                        .content(content)
                        .build());
            }
            messages.add(com.alibaba.dashscope.common.Message.builder()
                    .role(com.alibaba.dashscope.common.Role.USER.getValue())
                    .content(prompt)
                    .build());

            logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）",
                    messages.size(), history.size());
            logger.info("开始调用AI模型流式接口...");

            com.alibaba.dashscope.aigc.generation.Generation generation =
                    new com.alibaba.dashscope.aigc.generation.Generation();
            com.alibaba.dashscope.aigc.generation.GenerationParam param =
                    com.alibaba.dashscope.aigc.generation.GenerationParam.builder()
                            .apiKey(apiKey)
                            .model(model)
                            .messages(messages)
                            .incrementalOutput(true)
                            .resultFormat("message")
                            .build();

            final StringBuilder finalContent = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean completed = new AtomicBoolean(false);

            // streamCall 是异步的，SSE 事件在 OkHttp 线程上交付
            // 使用 subscribe 确保 onNext/onComplete/onError 在正确的生命周期内触发
            generation.streamCall(param).subscribe(
                    result -> {
                        if (result.getOutput() != null
                                && result.getOutput().getChoices() != null
                                && !result.getOutput().getChoices().isEmpty()) {
                            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
                            if (content != null && !content.isEmpty()) {
                                finalContent.append(content);
                                callback.onContentChunk(content);
                            }
                        }
                    },
                    error -> {
                        if (!completed.compareAndSet(false, true)) return;
                        logger.error("流式调用异常: {}", error.getMessage());
                        try {
                            callback.onError(new RuntimeException("流式调用异常: " + error.getMessage()));
                        } finally {
                            latch.countDown();
                        }
                    },
                    () -> {
                        if (!completed.compareAndSet(false, true)) return;
                        logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());
                        try {
                            callback.onComplete(finalContent.toString(), "");
                        } finally {
                            latch.countDown();
                        }
                    }
            );

            // 阻塞等待 SSE 流完整交付
            latch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("流式生成被中断");
            callback.onError(new RuntimeException("查询被中断", e));
        } catch (Exception e) {
            logger.error("生成答案失败: {}", e.getMessage());
            callback.onError(e);
        }
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onSearchResults(List<HybridSearchService.HybridSearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
