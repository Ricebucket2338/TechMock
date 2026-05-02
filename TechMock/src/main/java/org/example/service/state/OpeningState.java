package org.example.service.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.InterviewTurnResult;
import org.example.entity.InterviewSession;
import org.example.entity.Message;
import org.example.enums.InterviewPhase;
import org.example.repository.MessageRepository;
import org.example.service.PromptTemplateService;

import java.util.List;
import java.util.Map;

/**
 * 开场阶段状态
 * 职责：根据面试类型选择合适的 prompt → 调用 LLM 生成第一个问题 → 转入 QUESTIONING
 */
public class OpeningState implements InterviewState {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PromptTemplateService promptTemplateService;
    private final InterviewLLMCaller llmCaller;
    private final MessageRepository messageRepository;

    public OpeningState(PromptTemplateService promptTemplateService,
                        InterviewLLMCaller llmCaller,
                        MessageRepository messageRepository) {
        this.promptTemplateService = promptTemplateService;
        this.llmCaller = llmCaller;
        this.messageRepository = messageRepository;
    }

    @Override
    public HandleResult handle(InterviewContext ctx) {
        InterviewSession session = ctx.getSession();
        String interviewType = session.getInterviewType();

        // 确保 currentDirection 已设置（从全局队列取第一个方向）
        if (session.getCurrentDirection() == null || session.getCurrentDirection().isEmpty()) {
            ensureCurrentDirection(session);
        }

        String systemPrompt;

        if ("tech".equalsIgnoreCase(interviewType)) {
            systemPrompt = promptTemplateService.buildTechFundamentalsPrompt(
                    ctx.getDirectionNames(),
                    session.getCurrentDirection(),
                    ctx.getFirstTopics());
        } else {
            systemPrompt = promptTemplateService.buildNonTechIceBreakerPrompt();
        }

        // 调用 LLM 生成开场问题
        InterviewTurnResult turnResult = llmCaller.call(systemPrompt, null);
        ctx.setTurnResult(turnResult);

        // 保存面试官消息 + 内部评估
        saveAssistantMessage(session, turnResult.getQuestion());
        appendAnalysis(session, turnResult.getAnalysis());

        // 返回问题文本，转入问答阶段
        String question = turnResult != null ? turnResult.getQuestion() : "您好，我们现在开始面试。";
        return new HandleResult(InterviewPhase.QUESTIONING, question);
    }

    @Override
    public InterviewPhase phase() {
        return InterviewPhase.OPENING;
    }

    @SuppressWarnings("unchecked")
    private void ensureCurrentDirection(InterviewSession session) {
        // 优先从新 directionQueue 取
        if (session.getDirectionQueue() != null && !session.getDirectionQueue().isEmpty()) {
            try {
                List<Map<String, Object>> queue = MAPPER.readValue(
                        session.getDirectionQueue(),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
                if (!queue.isEmpty()) {
                    session.setCurrentDirection((String) queue.get(0).get("direction"));
                    return;
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
                session.setCurrentDirection((String) queue.get(0).get("direction"));
            }
        } catch (Exception ignored) {
        }
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
}
