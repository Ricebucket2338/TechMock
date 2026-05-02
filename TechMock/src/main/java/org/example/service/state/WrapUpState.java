package org.example.service.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.InterviewSession;
import org.example.entity.Message;
import org.example.enums.InterviewPhase;
import org.example.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 收尾阶段状态
 * 职责：拉取全部对话记录 + 内部评估 → 调用 LLM 生成结构化 JSON 报告 → 转入 COMPLETED
 */
public class WrapUpState implements InterviewState {

    private static final Logger logger = LoggerFactory.getLogger(WrapUpState.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessageRepository messageRepository;
    private final InterviewLLMCaller llmCaller;

    public WrapUpState(MessageRepository messageRepository, InterviewLLMCaller llmCaller) {
        this.messageRepository = messageRepository;
        this.llmCaller = llmCaller;
    }

    @Override
    public HandleResult handle(InterviewContext ctx) {
        InterviewSession session = ctx.getSession();

        // 1. 拉取全部对话消息
        List<Message> allMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());

        // 2. 拼接对话文本 + 内部评估记录
        StringBuilder conversationText = new StringBuilder();
        for (Message msg : allMessages) {
            String role = "user".equals(msg.getRole()) ? "候选人" : "面试官";
            conversationText.append(role).append(": ").append(msg.getContent()).append("\n\n");
        }

        if (session.getSummary() != null && !session.getSummary().isEmpty()) {
            conversationText.append("\n--- 面试过程内部评估记录 ---\n");
            conversationText.append(session.getSummary()).append("\n");
        }

        // 3. 注入已覆盖方向
        if (session.getDirectionOrder() != null) {
            try {
                List<String> dirs = MAPPER.readValue(session.getDirectionOrder(),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                conversationText.append("\n本次面试考察方向: ").append(String.join(", ", dirs)).append("\n");
            } catch (JsonProcessingException ignored) {
            }
        }

        // 4. 构建报告生成 prompt → 调用 LLM
        String reportPrompt = "你是一个专业的面试官。请根据以下完整的面试对话和内部评估记录，生成结构化的 JSON 面试评估报告。\n\n"
                + conversationText + "\n\n请严格输出以下 JSON 格式（不要输出任何其他文字）：\n"
                + "{\n"
                + "  \"overallGrade\": \"S/A/B/C/D 之一\",\n"
                + "  \"skillAssessments\": [\n"
                + "    {\"skillName\": \"技能名\", \"level\": 1-5, \"evidence\": \"评估依据\"}\n"
                + "  ],\n"
                + "  \"strengths\": [\"优势1\", \"优势2\"],\n"
                + "  \"weaknesses\": [\"弱点1\", \"弱点2\"],\n"
                + "  \"suggestions\": [\"改进建议1\", \"改进建议2\"]\n"
                + "}\n"
                + "语言简洁专业，使用中文。";

        String reportJson;
        try {
            String raw = llmCaller.callReport(reportPrompt, "请生成面试评估报告。");
            if (raw != null) {
                reportJson = extractJson(raw);
            } else {
                reportJson = defaultReport();
            }
        } catch (Exception e) {
            logger.error("报告生成失败: {}", e.getMessage());
            reportJson = defaultReport();
        }

        // 5. 存入 session
        session.setReportData(reportJson);

        // 6. 转入完成状态
        return new HandleResult(InterviewPhase.COMPLETED, reportJson);
    }

    @Override
    public InterviewPhase phase() {
        return InterviewPhase.WRAP_UP;
    }

    private String extractJson(String text) {
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String defaultReport() {
        return "{\"overallGrade\":\"D\",\"strengths\":[],\"weaknesses\":[],\"suggestions\":[],\"skillAssessments\":[]}";
    }
}
