package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.state.InterviewLLMCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 候选人行为意图分析 — 纯 LLM 语义理解，无关键词兜底
 *
 * LLM 调用失败时默认返回 KNOWLEDGE_GAP（安全降级）：
 * 误删考点的代价 < 候选人不了解却继续追问的代价
 */
@Component
public class BehaviorAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(BehaviorAnalyzer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    @Lazy
    private InterviewLLMCaller llmCaller;

    public enum BehaviorType {
        NORMAL,
        KNOWLEDGE_GAP,
        UNWILLING,
        AGGRESSIVE,
        EVASIVE
    }

    public static class AnalysisResult {
        public BehaviorType behavior;
        public String reason;

        public AnalysisResult(BehaviorType behavior, String reason) {
            this.behavior = behavior;
            this.reason = reason;
        }
    }

    /**
     * 分析用户回答的行为类型
     *
     * @param answer    候选人的回答文本
     * @param direction 当前面试方向（可为 null）
     * @param point     当前考点名称（可为 null）
     */
    public AnalysisResult analyze(String answer, String direction, String point) {
        if (answer == null || answer.trim().isEmpty()) {
            return new AnalysisResult(BehaviorType.EVASIVE, "回答为空");
        }

        if (llmCaller == null) {
            logger.warn("LLM 调用器未初始化，默认返回 KNOWLEDGE_GAP");
            return new AnalysisResult(BehaviorType.KNOWLEDGE_GAP, "LLM 不可用，安全降级");
        }

        String prompt = buildPrompt(answer.trim(), direction, point);
        String raw;
        try {
            raw = llmCaller.callBehavior(prompt);
        } catch (Exception e) {
            logger.warn("LLM 行为分析失败，默认返回 KNOWLEDGE_GAP: {}", e.getMessage());
            return new AnalysisResult(BehaviorType.KNOWLEDGE_GAP, "LLM 调用失败，安全降级");
        }

        if (raw == null || raw.isEmpty()) {
            logger.warn("LLM 行为分析返回为空，默认返回 KNOWLEDGE_GAP");
            return new AnalysisResult(BehaviorType.KNOWLEDGE_GAP, "LLM 返回为空，安全降级");
        }

        return parseResult(raw);
    }

    /**
     * 兼容旧调用，等价于 analyze(answer, null, null)
     */
    public AnalysisResult analyze(String answer) {
        return analyze(answer, null, null);
    }

    private String buildPrompt(String answer, String direction, String point) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个行为分析专家，负责判断面试候选人的回答意图。\n\n");
        if (direction != null && point != null) {
            sb.append("当前面试考点：").append(direction).append(" - ").append(point).append("\n\n");
        }
        sb.append("候选人的回答：「").append(answer).append("」\n\n");
        sb.append("判断标准（按优先级，只返回最匹配的一项）：\n");
        sb.append("1. AGGRESSIVE: 辱骂、人身攻击、挑衅、侮辱性语言\n");
        sb.append("2. UNWILLING: 明确表达不想继续面试、拒绝回答问题、要求停止\n");
        sb.append("3. KNOWLEDGE_GAP: 表示不知道/不会/没接触过/不了解/没概念/不懂/不清楚等\n");
        sb.append("   任何形式的\"我不了解这个知识点\"都属于此类，无论措辞如何\n");
        sb.append("4. EVASIVE: 回答极短（1-3个字）、敷衍、没有实质内容但也没说不了解\n");
        sb.append("5. NORMAL: 正常回答问题，有实质内容\n\n");
        sb.append("请以 JSON 格式返回：\n");
        sb.append("{\n");
        sb.append("  \"behavior\": \"AGGRESSIVE | UNWILLING | KNOWLEDGE_GAP | EVASIVE | NORMAL\",\n");
        sb.append("  \"reason\": \"一句话说明判断理由\"\n");
        sb.append("}");
        return sb.toString();
    }

    private AnalysisResult parseResult(String raw) {
        try {
            String json = extractJson(raw);
            JsonNode root = MAPPER.readTree(json);
            String behaviorStr = root.has("behavior") ? root.get("behavior").asText().trim() : "";
            String reason = root.has("reason") ? root.get("reason").asText() : "";

            BehaviorType behavior;
            try {
                behavior = BehaviorType.valueOf(behaviorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("LLM 返回未知行为类型: {}，默认返回 KNOWLEDGE_GAP", behaviorStr);
                return new AnalysisResult(BehaviorType.KNOWLEDGE_GAP, "LLM 返回格式异常，安全降级");
            }

            return new AnalysisResult(behavior, reason);
        } catch (Exception e) {
            logger.warn("解析行为分析结果失败，默认返回 KNOWLEDGE_GAP: {}", e.getMessage());
            return new AnalysisResult(BehaviorType.KNOWLEDGE_GAP, "解析失败，安全降级");
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
