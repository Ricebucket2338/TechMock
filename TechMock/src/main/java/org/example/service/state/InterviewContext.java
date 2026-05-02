package org.example.service.state;

import org.example.dto.InterviewTurnResult;
import org.example.entity.InterviewSession;
import org.example.entity.Message;
import org.example.enums.InterviewPhase;

import java.util.List;

/**
 * 面试状态机上下文
 * 封装单个问答请求所需的全部数据：session 实体、用户输入、行为分析结果、
 * 以及状态处理过程中产生的中间数据
 */
public class InterviewContext {

    private final InterviewSession session;
    private final String userAnswer;
    private InterviewPhase currentPhase;
    private String behaviorNote;
    private String terminateReason;
    private InterviewTurnResult turnResult;

    // Opening 阶段注入的数据
    private List<String> directionNames;
    private List<String> firstTopics;
    private String selectedSkills;

    // 结果标记
    private boolean shouldWrapUp;
    private String nextQuestion;

    public InterviewContext(InterviewSession session, String userAnswer, InterviewPhase currentPhase) {
        this.session = session;
        this.userAnswer = userAnswer;
        this.currentPhase = currentPhase;
    }

    public InterviewContext(InterviewSession session, InterviewPhase currentPhase) {
        this(session, null, currentPhase);
    }

    public InterviewSession getSession() {
        return session;
    }

    public String getUserAnswer() {
        return userAnswer;
    }

    public InterviewPhase getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(InterviewPhase currentPhase) {
        this.currentPhase = currentPhase;
    }

    public String getBehaviorNote() {
        return behaviorNote;
    }

    public void setBehaviorNote(String behaviorNote) {
        this.behaviorNote = behaviorNote;
    }

    public String getTerminateReason() {
        return terminateReason;
    }

    public void setTerminateReason(String terminateReason) {
        this.terminateReason = terminateReason;
    }

    public InterviewTurnResult getTurnResult() {
        return turnResult;
    }

    public void setTurnResult(InterviewTurnResult turnResult) {
        this.turnResult = turnResult;
    }

    public List<String> getDirectionNames() {
        return directionNames;
    }

    public void setDirectionNames(List<String> directionNames) {
        this.directionNames = directionNames;
    }

    public List<String> getFirstTopics() {
        return firstTopics;
    }

    public void setFirstTopics(List<String> firstTopics) {
        this.firstTopics = firstTopics;
    }

    public String getSelectedSkills() {
        return selectedSkills;
    }

    public void setSelectedSkills(String selectedSkills) {
        this.selectedSkills = selectedSkills;
    }

    public boolean isShouldWrapUp() {
        return shouldWrapUp;
    }

    public void setShouldWrapUp(boolean shouldWrapUp) {
        this.shouldWrapUp = shouldWrapUp;
    }

    public String getNextQuestion() {
        return nextQuestion;
    }

    public void setNextQuestion(String nextQuestion) {
        this.nextQuestion = nextQuestion;
    }

    /** 构建最近对话摘要文本，用于注入 prompt */
    public static String buildConversationSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = "user".equals(msg.getRole()) ? "候选人" : "面试官";
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
