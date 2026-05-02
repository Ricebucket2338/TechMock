package org.example.service.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.dto.InterviewTurnResult;
import org.example.entity.InterviewSession;
import org.example.entity.Message;
import org.example.enums.InterviewPhase;
import org.example.repository.MessageRepository;
import org.example.service.BehaviorAnalyzer;
import org.example.service.InterviewConfig;
import org.example.service.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 问答循环阶段状态 — 基于方向队列 + 考点计数器的面试引擎
 *
 * 执行流程：
 * 1. 获取"上一轮问的考点"（lastAskedDirection/lastAskedPoint），首轮跳过
 * 2. LLM 分析候选人回答意图
 * 3. KNOWLEDGE_GAP → 用步骤1的考点直接删除，不依赖 coveredTopics
 * 4. 终止条件检查
 * 5. 保存消息 + 轮数++
 * 6. 阶段切换检查
 * 7. 获取下一个考点，记录到 lastAskedDirection/lastAskedPoint
 * 8. 构建 prompt → LLM 生成问题
 * 9. 保存结果 + 返回问题文本
 */
public class QuestioningState implements InterviewState {

    private static final Logger logger = LoggerFactory.getLogger(QuestioningState.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptTemplateService promptTemplateService;
    private final BehaviorAnalyzer behaviorAnalyzer;
    private final MessageRepository messageRepository;
    private final InterviewLLMCaller llmCaller;

    public QuestioningState(PromptTemplateService promptTemplateService,
                            org.example.service.InterviewConfig interviewConfig,
                            BehaviorAnalyzer behaviorAnalyzer,
                            MessageRepository messageRepository,
                            InterviewLLMCaller llmCaller) {
        this.promptTemplateService = promptTemplateService;
        this.behaviorAnalyzer = behaviorAnalyzer;
        this.messageRepository = messageRepository;
        this.llmCaller = llmCaller;
    }

    @Override
    public HandleResult handle(InterviewContext ctx) {
        InterviewSession session = ctx.getSession();
        String userAnswer = ctx.getUserAnswer();

        // 1. 确定"上一轮问的考点"，用于 KNOWLEDGE_GAP 删除
        //    首轮（turnCount==0）没有上一轮，跳过
        String lastDirection = null;
        String lastPoint = null;
        boolean isFirstTurn = session.getTurnCount() == 0;
        if (!isFirstTurn) {
            lastDirection = session.getLastAskedDirection();
            lastPoint = session.getLastAskedPoint();
        }

        // 2. LLM 意图分析（注入考点上下文）
        BehaviorAnalyzer.AnalysisResult analysis = behaviorAnalyzer.analyze(userAnswer, lastDirection, lastPoint);
        String behaviorNote = recordBehavior(session, analysis);
        ctx.setBehaviorNote(behaviorNote);

        // 3. 知识不足处理：直接用 lastDirection/lastPoint 删除，不依赖 coveredTopics
        if (analysis.behavior == BehaviorAnalyzer.BehaviorType.KNOWLEDGE_GAP) {
            handleKnowledgeGap(session, lastDirection, lastPoint);
        }

        // 4. 终止条件检查（按优先级）
        String terminateReason = shouldTerminate(analysis, session);
        if (terminateReason != null) {
            ctx.setTerminateReason(terminateReason);
            return new HandleResult(InterviewPhase.TERMINATED, "TERMINATE:" + terminateReason);
        }

        // 5. 保存用户消息 + 轮数 +1
        saveUserMessage(session, userAnswer);
        session.setTurnCount(session.getTurnCount() + 1);

        // 6. 自然结束条件检查
        if (shouldWrapUp(session)) {
            return new HandleResult(InterviewPhase.WRAP_UP, "WRAP_UP");
        }

        // 7. 阶段切换：BASIC 队列耗尽 → 切换到 SCENARIO
        String phaseType = session.getCurrentPhaseType();
        if ("BASIC".equals(phaseType) && InterviewConfig.isDirectionQueueExhausted(session.getDirectionQueue())) {
            logger.info("八股文队列已耗尽，切换到场景题阶段");
            session.setCurrentPhaseType("SCENARIO");
            phaseType = "SCENARIO";
        }

        // 8. 确定下一个问题
        String nextDirection = null;
        String nextPoint = null;
        String scenarioQuestion = null;

        if ("SCENARIO".equals(phaseType)) {
            scenarioQuestion = InterviewConfig.getNextScenarioQuestion(session.getScenarioQueue());
            if (scenarioQuestion != null) {
                session.setScenarioQueue(
                        InterviewConfig.incrementScenarioAsked(session.getScenarioQueue(), scenarioQuestion));
            }
        } else {
            // 八股文阶段：从队列获取下一个考点
            Map<String, String> nextPointInfo = InterviewConfig.getNextPointFromQueue(session.getDirectionQueue());
            if (nextPointInfo != null) {
                nextDirection = nextPointInfo.get("direction");
                nextPoint = nextPointInfo.get("pointName");
                // 标记已问（达到上限会物理删除）
                session.setDirectionQueue(
                        InterviewConfig.incrementPointAsked(session.getDirectionQueue(), nextDirection, nextPoint));
                // 清理空方向
                session.setDirectionQueue(
                        InterviewConfig.cleanEmptyDirections(session.getDirectionQueue()));
                session.setCurrentDirection(nextDirection);
            }
        }

        // 记录本轮问的考点，供下一轮 KNOWLEDGE_GAP 删除使用
        session.setLastAskedDirection(nextDirection);
        session.setLastAskedPoint(nextPoint);

        // 队列耗尽兜底
        if (nextDirection == null && scenarioQuestion == null) {
            return new HandleResult(InterviewPhase.WRAP_UP, "WRAP_UP");
        }

        // 9. 获取最近对话历史
        List<Message> recentMessages = messageRepository.findRecentBySession(session.getId(), 10);
        String conversationSummary = InterviewContext.buildConversationSummary(recentMessages);

        // 10. 构建 prompt → 调用 LLM 生成问题
        String systemPrompt;
        if ("SCENARIO".equals(phaseType)) {
            systemPrompt = buildScenarioPrompt(session, conversationSummary, scenarioQuestion, behaviorNote);
        } else {
            systemPrompt = buildBasicPrompt(session, conversationSummary, nextDirection, nextPoint, behaviorNote);
        }

        InterviewTurnResult turnResult = llmCaller.call(systemPrompt, userAnswer);

        // 11. 保存结果
        saveAssistantMessage(session, turnResult.getQuestion());
        appendAnalysis(session, turnResult.getAnalysis());
        updateTopicTracking(session, nextDirection, turnResult.getAnalysis() != null ? turnResult.getAnalysis().getCoveredTopic() : null);

        if (!behaviorNote.isEmpty()) {
            appendBehaviorWarning(session, behaviorNote);
        }

        // 12. 返回问题文本
        return new HandleResult(InterviewPhase.QUESTIONING, turnResult.getQuestion());
    }

    @Override
    public InterviewPhase phase() {
        return InterviewPhase.QUESTIONING;
    }

    // ==================== 终止条件（按优先级） ====================

    private String shouldTerminate(BehaviorAnalyzer.AnalysisResult analysis, InterviewSession session) {
        // 优先级1：攻击性 — 零容忍，累计 ≥ 2 次立即终止
        if (analysis.behavior == BehaviorAnalyzer.BehaviorType.AGGRESSIVE) {
            if (session.getAggressiveCount() >= 2) {
                return "候选人多次出现攻击性言论，面试终止";
            }
        }

        // 优先级2：终止意愿 — LLM 判断候选人不想继续了，立即终止
        if (analysis.behavior == BehaviorAnalyzer.BehaviorType.UNWILLING) {
            return "候选人表达终止面试意愿，面试终止";
        }

        return null;
    }

    // ==================== 行为记录 ====================

    private String recordBehavior(InterviewSession session, BehaviorAnalyzer.AnalysisResult analysis) {
        switch (analysis.behavior) {
            case AGGRESSIVE:
                session.setAggressiveCount(session.getAggressiveCount() + 1);
                return "[警告] 候选人出现攻击性言论 (第" + session.getAggressiveCount() + "次): " + analysis.reason;
            case UNWILLING:
                return "[严重] 候选人表达终止意愿: " + analysis.reason;
            case KNOWLEDGE_GAP:
                return "[注意] 候选人表示不了解当前知识点: " + analysis.reason;
            case EVASIVE:
                return "[注意] 候选人回答敷衍: " + analysis.reason;
            default:
                return "";
        }
    }

    private void appendBehaviorWarning(InterviewSession session, String note) {
        try {
            ArrayNode warnings;
            String existing = session.getBehaviorWarnings();
            if (existing != null && !existing.isEmpty()) {
                warnings = (ArrayNode) MAPPER.readTree(existing);
            } else {
                warnings = MAPPER.createArrayNode();
            }
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("turn", session.getTurnCount() + 1);
            entry.put("note", note);
            entry.put("time", LocalDateTime.now().format(TIME_FMT));
            warnings.add(entry);
            session.setBehaviorWarnings(MAPPER.writeValueAsString(warnings));
        } catch (Exception e) {
            logger.warn("追加行为警告失败: {}", e.getMessage());
        }
    }

    // ==================== 知识不足处理 ====================

    /**
     * KNOWLEDGE_GAP: 记录薄弱方向 + 物理删除当前考点
     * 使用 session.lastAskedDirection/lastAskedPoint，不依赖 coveredTopics
     */
    private void handleKnowledgeGap(InterviewSession session, String direction, String pointName) {
        // 记录薄弱方向
        if (direction != null && !direction.isEmpty()) {
            String weakAreas = session.getWeakAreas() != null ? session.getWeakAreas() : "[]";
            try {
                ArrayNode arr = (ArrayNode) MAPPER.readTree(weakAreas);
                boolean exists = false;
                for (var node : arr) {
                    if (node.asText().equals(direction)) { exists = true; break; }
                }
                if (!exists) {
                    arr.add(direction);
                    session.setWeakAreas(MAPPER.writeValueAsString(arr));
                }
            } catch (Exception e) {
                session.setWeakAreas("[\"" + direction + "\"]");
            }
        }
        // 物理删除当前考点
        if (direction != null && pointName != null && session.getDirectionQueue() != null) {
            logger.info("KNOWLEDGE_GAP: 删除考点 [{}/{}]", direction, pointName);
            session.setDirectionQueue(
                    InterviewConfig.removePoint(session.getDirectionQueue(), direction, pointName));
        } else if (direction == null || pointName == null) {
            logger.warn("KNOWLEDGE_GAP: 无法删除考点，direction 或 pointName 为 null");
        }
    }

    // ==================== 自然结束条件 ====================

    private boolean shouldWrapUp(InterviewSession session) {
        String phaseType = session.getCurrentPhaseType();

        // BASIC 阶段：检查方向队列是否耗尽
        if ("BASIC".equals(phaseType)) {
            return InterviewConfig.isDirectionQueueExhausted(session.getDirectionQueue());
        }

        // SCENARIO 阶段：检查场景题队列是否耗尽
        if ("SCENARIO".equals(phaseType)) {
            return InterviewConfig.isScenarioQueueExhausted(session.getScenarioQueue());
        }

        return true;
    }

    // ==================== Prompt 构建 ====================

    private String buildBasicPrompt(InterviewSession session, String conversationSummary,
                                     String direction, String point, String behaviorNote) {
        return promptTemplateService.buildBasicQuestionPrompt(
                conversationSummary, direction, point,
                session.getCoveredTopics(), behaviorNote);
    }

    private String buildScenarioPrompt(InterviewSession session, String conversationSummary,
                                        String scenarioQuestion, String behaviorNote) {
        return promptTemplateService.buildScenarioQuestionPrompt(
                conversationSummary, scenarioQuestion, behaviorNote);
    }

    // ==================== 消息持久化 ====================

    private void saveUserMessage(InterviewSession session, String content) {
        Message msg = new Message();
        msg.setSession(session);
        msg.setRole("user");
        msg.setContent(content);
        messageRepository.save(msg);
    }

    private void saveAssistantMessage(InterviewSession session, String content) {
        Message msg = new Message();
        msg.setSession(session);
        msg.setRole("assistant");
        msg.setContent(content);
        messageRepository.save(msg);
    }

    private void appendAnalysis(InterviewSession session, InterviewTurnResult.Analysis analysis) {
        if (analysis == null || analysis.getEvaluation() == null || analysis.getEvaluation().isEmpty()) {
            return;
        }
        String entry = String.format("[第%d轮] %s (评分:%d/5)\n",
                session.getTurnCount() + 1, analysis.getEvaluation(), analysis.getScore());
        String current = session.getSummary() != null ? session.getSummary() : "";
        session.setSummary(current + entry);
    }

    private void updateTopicTracking(InterviewSession session, String direction, String coveredTopic) {
        if (coveredTopic == null || coveredTopic.isEmpty()) return;
        if (direction != null) {
            session.setCurrentDirection(direction);
        }
        String current = session.getCoveredTopics() != null ? session.getCoveredTopics() : "";
        if (!current.contains(coveredTopic)) {
            session.setCoveredTopics(current.isEmpty() ? coveredTopic : current + "," + coveredTopic);
        }
    }
}
