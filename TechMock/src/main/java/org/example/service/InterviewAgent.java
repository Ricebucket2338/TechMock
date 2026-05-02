package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.InterviewReport;
import org.example.dto.InterviewTurnResult;
import org.example.entity.InterviewSession;
import org.example.enums.InterviewPhase;
import org.example.repository.MessageRepository;
import org.example.repository.SessionRepository;
import org.example.repository.UserRepository;
import org.example.service.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 面试 Agent —— 基于状态机模式重构
 *
 * 架构：
 * ┌─────────────────────────────────────────────┐
 * │              InterviewAgent                 │
 * │  (编排器：维护状态映射，委托给各 State 处理)    │
 * └──────────────┬──────────────────────────────┘
 *                │ handle(ctx) → state.handle(ctx)
 *       ┌────────┼───────────────┬──────────┐
 *       ▼        ▼               ▼          ▼
 *  OpeningState  QuestioningState  WrapUpState  CompletedState
 *
 * 状态流转：
 *   OPENING → QUESTIONING ←→ QUESTIONING → WRAP_UP → COMPLETED
 *                                 │
 *                                 ↓ TERMINATED (行为违规)
 */
@Service
public class InterviewAgent implements InterviewLLMCaller {

    private static final Logger logger = LoggerFactory.getLogger(InterviewAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${rag.model:qwen-plus}")
    private String modelName;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InterviewConfig interviewConfig;

    @Autowired
    private BehaviorAnalyzer behaviorAnalyzer;

    // ==================== 状态机初始化 ====================

    private final Map<InterviewPhase, InterviewState> stateMap = new java.util.HashMap<>();

    @jakarta.annotation.PostConstruct
    public void initStateMachine() {
        stateMap.put(InterviewPhase.OPENING,
                new OpeningState(promptTemplateService, this, messageRepository));
        stateMap.put(InterviewPhase.QUESTIONING,
                new QuestioningState(promptTemplateService, interviewConfig, behaviorAnalyzer,
                        messageRepository, this));
        stateMap.put(InterviewPhase.WRAP_UP,
                new WrapUpState(messageRepository, this));
        stateMap.put(InterviewPhase.COMPLETED,
                new CompletedState());
        logger.info("面试状态机初始化完成: {}", stateMap.keySet());
    }

    // ==================== LLM 调用器实现 ====================

    @Override
    public InterviewTurnResult call(String systemPrompt, String userMessage) {
        try {
            String userContent = userMessage != null
                    ? "候选人的回答：" + userMessage + "\n\n请根据这个回答生成下一个问题。"
                    : "请开始面试的第一个问题。";

            String rawContent = callDashScope(systemPrompt, userContent, 0.7f);
            if (rawContent != null && !rawContent.isEmpty()) {
                return parseTurnResult(rawContent);
            }
            return fallbackResult("抱歉，系统暂时无法处理，请稍后重试。");
        } catch (Exception e) {
            logger.error("面试 LLM 调用失败: {}", e.getMessage());
            return fallbackResult("抱歉，系统出现错误，请稍后重试。");
        }
    }

    @Override
    public String callReport(String systemPrompt, String userMessage) {
        return callDashScope(systemPrompt, userMessage, 0.3f);
    }

    @Override
    public String callBehavior(String systemPrompt) {
        return callDashScope(systemPrompt, "", 0.2f);
    }

    // ==================== 启动面试 ====================

    public InterviewStartResponse startInterview(String userId, String interviewType,
                                               List<String> selectedSkills) {
        String sessionId = UUID.randomUUID().toString();
        org.example.entity.User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        InterviewSession session = new InterviewSession();
        session.setId(sessionId);
        session.setUser(user);
        session.setMode("interview");
        session.setInterviewType(interviewType);
        session.setStatus("active");
        session.setPhase(InterviewPhase.OPENING.name());
        session.setTurnCount(0);
        session.setCoveredTopics("");
        session.setTopicQuestionMap("{}");
        session.setAggressiveCount(0);
        session.setUncooperativeCount(0);
        session.setCurrentDirectionIdx(0);
        session.setStartTime(LocalDateTime.now());

        // 随机选择方向（技术面强制包含AI大模型）
        List<InterviewConfig.TechDirection> directions;
        if ("tech".equalsIgnoreCase(interviewType)) {
            directions = interviewConfig.selectRandomDirections(InterviewConfig.BASIC_MAX_DIRECTIONS, true);
        } else {
            directions = interviewConfig.selectRandomDirections(3);
        }

        // 保存方向顺序到 session
        try {
            session.setDirectionOrder(MAPPER.writeValueAsString(
                    directions.stream().map(d -> d.name).toList()));
        } catch (Exception e) {
            logger.warn("序列化 directionOrder 失败", e);
        }

        // 初始化八股文方向队列
        if ("tech".equalsIgnoreCase(interviewType)) {
            session.setCurrentPhaseType("BASIC");
            try {
                session.setDirectionQueue(
                        interviewConfig.buildBasicDirectionQueue(directions));
            } catch (Exception e) {
                logger.warn("序列化 directionQueue 失败", e);
            }
            try {
                session.setScenarioQueue(
                        interviewConfig.generateScenarioQuestions());
            } catch (Exception e) {
                logger.warn("序列化 scenarioQueue 失败", e);
            }
        }

        // 保留旧队列兼容（OpeningState 可能仍会用到）
        try {
            session.setGlobalTopicQueue(
                    interviewConfig.buildGlobalTopicQueue(directions));
        } catch (Exception e) {
            logger.warn("序列化 globalTopicQueue 失败", e);
        }

        sessionRepository.save(session);

        // 构建开场 context → 调用 OPENING 状态处理
        InterviewContext ctx = new InterviewContext(session, InterviewPhase.OPENING);
        ctx.setDirectionNames(directions.stream().map(d -> d.name).toList());
        ctx.setFirstTopics(getFirstTopicsFromQueue(session));
        ctx.setSelectedSkills(selectedSkills != null && !selectedSkills.isEmpty()
                ? String.join("、", selectedSkills) : null);

        InterviewState openingState = stateMap.get(InterviewPhase.OPENING);
        InterviewState.HandleResult result = openingState.handle(ctx);

        // 更新 phase
        session.setPhase(result.getNextPhase().name());
        sessionRepository.save(session);

        // 构建返回
        InterviewStartResponse response = new InterviewStartResponse();
        response.setSessionId(sessionId);
        response.setOpeningMessage(result.getResponse());
        response.setPhase(result.getNextPhase().getDisplayName());
        return response;
    }

    /**
     * 从方向队列取第一个方向的第一考点作为开场
     */
    @SuppressWarnings("unchecked")
    private List<String> getFirstTopicsFromQueue(InterviewSession session) {
        // 优先从新 directionQueue 取
        if (session.getDirectionQueue() != null && !session.getDirectionQueue().isEmpty()) {
            try {
                List<Map<String, Object>> queue = MAPPER.readValue(
                        session.getDirectionQueue(),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
                if (!queue.isEmpty()) {
                    Map<String, Object> firstDir = queue.get(0);
                    List<Map<String, Object>> points = (List<Map<String, Object>>) firstDir.get("points");
                    if (points != null && !points.isEmpty()) {
                        return List.of((String) points.get(0).get("name"));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 回退到旧 globalTopicQueue
        try {
            List<Map<String, Object>> queue = MAPPER.readValue(
                    session.getGlobalTopicQueue(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            if (!queue.isEmpty()) {
                return List.of((String) queue.get(0).get("topic"));
            }
        } catch (Exception ignored) {
        }
        return List.of("基础");
    }

    // ==================== 处理回答 —— 状态机委托 ====================

    public String handleUserAnswer(String sessionId, String userAnswer) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("面试会话不存在: " + sessionId));

        if (InterviewPhase.valueOf(session.getPhase()).isTerminal()) {
            throw new IllegalStateException("面试已结束，无法继续");
        }

        // 构建 context
        InterviewContext ctx = new InterviewContext(session, userAnswer, InterviewPhase.valueOf(session.getPhase()));

        // 委托给当前状态处理
        InterviewState currentState = stateMap.get(ctx.getCurrentPhase());
        if (currentState == null) {
            throw new IllegalStateException("未知状态: " + ctx.getCurrentPhase());
        }

        InterviewState.HandleResult result = currentState.handle(ctx);

        // 更新 phase
        session.setPhase(result.getNextPhase().name());

        // 根据下一状态决定行为
        if (result.getNextPhase() == InterviewPhase.WRAP_UP) {
            ctx.setCurrentPhase(InterviewPhase.WRAP_UP);
            InterviewState wrapUpState = stateMap.get(InterviewPhase.WRAP_UP);
            InterviewState.HandleResult wrapResult = wrapUpState.handle(ctx);
            session.setPhase(wrapResult.getNextPhase().name());
            sessionRepository.save(session);
            return "REPORT:" + wrapResult.getResponse();
        }

        if (result.getNextPhase() == InterviewPhase.TERMINATED) {
            session.setStatus("completed");
            sessionRepository.save(session);
            String report = generateStructuredReport(session);
            return "TERMINATE:" + ctx.getTerminateReason() + ":::" + report;
        }

        if (result.getNextPhase() == InterviewPhase.COMPLETED) {
            session.setStatus("completed");
            sessionRepository.save(session);
            return "REPORT:" + result.getResponse();
        }

        // 继续问答
        sessionRepository.save(session);
        return result.getResponse();
    }

    // ==================== 结束面试 ====================

    public InterviewReport endInterview(String sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("面试会话不存在: " + sessionId));

        session.setStatus("completed");
        session.setPhase(InterviewPhase.COMPLETED.name());
        sessionRepository.save(session);

        String reportJson = generateStructuredReport(session);

        InterviewReport report = new InterviewReport();
        report.setSessionId(sessionId);
        report.setInterviewType(session.getInterviewType());
        report.setTargetPosition(session.getTargetPosition());
        report.setOverallRecommendation(reportJson);

        return report;
    }

    // ==================== 获取报告 ====================

    public String getReport(String sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("面试会话不存在: " + sessionId));
        if (session.getReportData() != null && !session.getReportData().isEmpty()) {
            return session.getReportData();
        }
        return generateStructuredReport(session);
    }

    // ==================== 报告生成 ====================

    private String generateStructuredReport(InterviewSession session) {
        InterviewContext ctx = new InterviewContext(session, InterviewPhase.WRAP_UP);
        InterviewState wrapUpState = stateMap.get(InterviewPhase.WRAP_UP);
        InterviewState.HandleResult result = wrapUpState.handle(ctx);
        session.setReportData(result.getResponse());
        sessionRepository.save(session);
        return result.getResponse();
    }

    // ==================== LLM 底层调用 ====================

    private String callDashScope(String systemPrompt, String userPrompt, float temperature) {
        try {
            List<com.alibaba.dashscope.common.Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(com.alibaba.dashscope.common.Message.builder()
                        .role(com.alibaba.dashscope.common.Role.SYSTEM.getValue())
                        .content(systemPrompt)
                        .build());
            }
            messages.add(com.alibaba.dashscope.common.Message.builder()
                    .role(com.alibaba.dashscope.common.Role.USER.getValue())
                    .content(userPrompt)
                    .build());

            com.alibaba.dashscope.aigc.generation.Generation generation =
                    new com.alibaba.dashscope.aigc.generation.Generation();
            com.alibaba.dashscope.aigc.generation.GenerationParam param =
                    com.alibaba.dashscope.aigc.generation.GenerationParam.builder()
                            .apiKey(dashScopeApiKey)
                            .model(modelName)
                            .messages(messages)
                            .temperature(temperature)
                            .resultFormat("message")
                            .build();

            var result = generation.call(param);
            if (result != null && result.getOutput() != null
                    && result.getOutput().getChoices() != null
                    && !result.getOutput().getChoices().isEmpty()) {
                return result.getOutput().getChoices().get(0).getMessage().getContent();
            }
            return null;
        } catch (Exception e) {
            logger.error("DashScope 调用异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 LLM 返回的 JSON，包含三层防护：
     * 1. 清洗 markdown 代码块标记
     * 2. 解析失败时返回兜底提示，不泄漏原始 JSON
     * 3. 泄漏检测：拦截类似 JSON 的 question 字段
     */
    private InterviewTurnResult parseTurnResult(String rawContent) {
        // 1. 清洗 markdown 代码块标记
        String cleaned = cleanMarkdownJson(rawContent);
        String jsonStr = extractJson(cleaned);

        try {
            JsonNode root = MAPPER.readTree(jsonStr);
            String question = root.has("question") ? root.get("question").asText().trim() : null;
            if (question == null || question.isEmpty()) {
                throw new IllegalArgumentException("question 字段为空");
            }

            // 2. 泄漏检测：question 看起来像 JSON 而非正常句子
            if (question.startsWith("{") && !question.contains("？") && !question.contains("?")
                    && !question.contains("。") && !question.contains(",")) {
                logger.error("检测到 JSON 泄漏: {}", question.substring(0, Math.min(80, question.length())));
                return fallbackResult("抱歉，系统暂时无法处理，请稍后重试。");
            }

            InterviewTurnResult turnResult = new InterviewTurnResult();
            turnResult.setQuestion(question);

            if (root.has("analysis")) {
                JsonNode analysisNode = root.get("analysis");
                InterviewTurnResult.Analysis analysis = new InterviewTurnResult.Analysis();
                analysis.setEvaluation(analysisNode.has("evaluation") ? analysisNode.get("evaluation").asText() : "");
                analysis.setScore(analysisNode.has("score") ? analysisNode.get("score").asInt() : 3);
                analysis.setCoveredTopic(analysisNode.has("coveredTopic") ? analysisNode.get("coveredTopic").asText() : "");
                turnResult.setAnalysis(analysis);
            } else {
                turnResult.setAnalysis(new InterviewTurnResult.Analysis());
            }

            return turnResult;
        } catch (Exception e) {
            // 3. 解析失败 → 返回兜底提示，绝不返回原始 JSON
            logger.warn("LLM JSON 解析失败: {}", e.getMessage());
            return fallbackResult("抱歉，系统暂时无法处理，请稍后重试。");
        }
    }

    private String cleanMarkdownJson(String text) {
        if (text == null) return "";
        text = text.replaceAll("(?s)^\\s*```(?:json)?\\s*", "");
        text = text.replaceAll("(?s)\\s*```\\s*$", "");
        return text.trim();
    }

    private String extractJson(String text) {
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private InterviewTurnResult fallbackResult(String question) {
        InterviewTurnResult result = new InterviewTurnResult();
        result.setQuestion(question);
        InterviewTurnResult.Analysis analysis = new InterviewTurnResult.Analysis();
        analysis.setEvaluation("");
        analysis.setScore(3);
        analysis.setCoveredTopic("");
        result.setAnalysis(analysis);
        return result;
    }

    // ==================== 启动结果 DTO ====================

    public static class InterviewStartResponse {
        private String sessionId;
        private String openingMessage;
        private List<String> selectedSkills;
        private String phase;
        private List<String> directionNames;
        private String currentDirection;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getOpeningMessage() { return openingMessage; }
        public void setOpeningMessage(String openingMessage) { this.openingMessage = openingMessage; }
        public List<String> getSelectedSkills() { return selectedSkills; }
        public void setSelectedSkills(List<String> selectedSkills) { this.selectedSkills = selectedSkills; }
        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = phase; }
        public List<String> getDirectionNames() { return directionNames; }
        public void setDirectionNames(List<String> directionNames) { this.directionNames = directionNames; }
        public String getCurrentDirection() { return currentDirection; }
        public void setCurrentDirection(String currentDirection) { this.currentDirection = currentDirection; }
    }
}
