package org.example.service;

import org.example.dto.SessionDetailVO;
import org.example.entity.InterviewSession;
import org.example.entity.Message;
import org.example.entity.User;
import org.example.repository.MessageRepository;
import org.example.repository.SessionRepository;
import org.example.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话管理与递进式摘要服务
 */
@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    private static final String DEFAULT_USER_ID = "user-001";
    private static final int SUMMARY_INTERVAL = 5;
    private static final int RECENT_MESSAGE_LIMIT = 10;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${rag.model:qwen-plus}")
    private String modelName;

    @PostConstruct
    public void init() {
        userRepository.findById(DEFAULT_USER_ID).orElseGet(() -> {
            User defaultUser = new User();
            defaultUser.setId(DEFAULT_USER_ID);
            defaultUser.setNickname("default_user");
            userRepository.save(defaultUser);
            logger.info("初始化默认用户: {}", DEFAULT_USER_ID);
            return defaultUser;
        });
    }

    /**
     * 获取或创建会话
     */
    @Transactional
    public InterviewSession getOrCreateSession(String sessionId, String mode) {
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        }
        return createSession(mode);
    }

    /**
     * 创建新会话
     */
    @Transactional
    public InterviewSession createSession(String mode) {
        InterviewSession session = new InterviewSession();
        session.setId(UUID.randomUUID().toString());
        session.setUser(getDefaultUser());
        session.setMode(mode != null ? mode : "qa");
        session.setStatus("active");
        session.setTurnCount(0);
        sessionRepository.save(session);
        logger.info("创建新会话: id={}, mode={}", session.getId(), session.getMode());
        return session;
    }

    /**
     * 保存用户消息
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        Message msg = new Message();
        msg.setSession(loadSession(sessionId));
        msg.setRole("user");
        msg.setContent(content);
        messageRepository.save(msg);
    }

    /**
     * 保存AI回复
     */
    @Transactional
    public void saveAssistantMessage(String sessionId, String content) {
        Message msg = new Message();
        msg.setSession(loadSession(sessionId));
        msg.setRole("assistant");
        msg.setContent(content);
        messageRepository.save(msg);
    }

    /**
     * 增加轮次计数，并检查是否需要生成摘要
     */
    @Transactional
    public void incrementTurnAndMaybeSummarize(String sessionId) {
        InterviewSession session = loadSession(sessionId);
        session.setTurnCount(session.getTurnCount() + 1);
        sessionRepository.save(session);

        if ("qa".equals(session.getMode()) && session.getTurnCount() % SUMMARY_INTERVAL == 0) {
            generateSummary(session);
        }
    }

    /**
     * 构建带摘要的 Prompt
     * 摘要 + 最近N轮消息 + 新问题
     */
    public String buildPromptWithSummary(String sessionId, String newQuestion) {
        InterviewSession session = loadSession(sessionId);
        StringBuilder sb = new StringBuilder();

        if (session.getSummary() != null && !session.getSummary().isEmpty()) {
            sb.append("【对话摘要】\n").append(session.getSummary()).append("\n\n");
        }

        List<Message> recent = messageRepository.findRecentBySession(sessionId, RECENT_MESSAGE_LIMIT);
        if (!recent.isEmpty()) {
            sb.append("【最近对话】\n");
            for (Message msg : recent) {
                String roleLabel = "user".equals(msg.getRole()) ? "用户" : "助手";
                sb.append(roleLabel).append(": ").append(msg.getContent()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("用户新问题: ").append(newQuestion);
        return sb.toString();
    }

    /**
     * 获取历史消息（用于传入 LLM 的 history 参数）
     */
    public List<java.util.Map<String, String>> getHistoryForLLM(String sessionId) {
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<java.util.Map<String, String>> history = new ArrayList<>();
        for (Message msg : messages) {
            java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent());
            history.add(map);
        }
        return history;
    }

    /**
     * 获取会话详情（含消息列表）
     */
    public SessionDetailVO getSessionDetail(String sessionId) {
        InterviewSession session = loadSession(sessionId);
        List<Message> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        SessionDetailVO vo = new SessionDetailVO();
        vo.setSessionId(session.getId());
        vo.setMode(session.getMode());
        vo.setStatus(session.getStatus());
        vo.setSummary(session.getSummary());
        vo.setTurnCount(session.getTurnCount());
        vo.setTargetPosition(session.getTargetPosition());
        vo.setOverallScore(session.getOverallScore());
        vo.setCreatedAt(session.getCreatedAt());
        vo.setUpdatedAt(session.getUpdatedAt());

        List<SessionDetailVO.MessageVO> messageVOs = new ArrayList<>();
        for (Message msg : messages) {
            SessionDetailVO.MessageVO mvo = new SessionDetailVO.MessageVO();
            mvo.setId(msg.getId());
            mvo.setRole(msg.getRole());
            mvo.setContent(msg.getContent());
            mvo.setCreatedAt(msg.getCreatedAt());
            messageVOs.add(mvo);
        }
        vo.setMessages(messageVOs);
        return vo;
    }

    /**
     * 列出用户所有会话
     */
    public List<InterviewSession> listSessions(String userId) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 完成会话
     */
    @Transactional
    public void completeSession(String sessionId) {
        InterviewSession session = loadSession(sessionId);
        session.setStatus("completed");
        sessionRepository.save(session);
        logger.info("会话已标记为完成: {}", sessionId);
    }

    /**
     * 删除会话（级联删除消息）
     */
    @Transactional
    public void deleteSession(String sessionId) {
        sessionRepository.deleteById(sessionId);
        logger.info("会话已删除: {}", sessionId);
    }

    /**
     * 获取用户最近活跃的会话（用于恢复）
     */
    public InterviewSession getLatestActiveSession() {
        List<InterviewSession> sessions = sessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                getDefaultUser().getId(), "active");
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    /**
     * 设置会话元数据
     */
    @Transactional
    public void setSessionMetadata(String sessionId, String targetPosition) {
        InterviewSession session = loadSession(sessionId);
        if (targetPosition != null) {
            session.setTargetPosition(targetPosition);
        }
        sessionRepository.save(session);
    }

    /**
     * 设置总体评分
     */
    @Transactional
    public void setOverallScore(String sessionId, String score) {
        InterviewSession session = loadSession(sessionId);
        session.setOverallScore(score);
        sessionRepository.save(session);
    }

    // ==================== 摘要生成 ====================

    /**
     * 递进式摘要生成
     */
    private void generateSummary(InterviewSession session) {
        try {
            String oldSummary = session.getSummary() != null ? session.getSummary() : "";
            List<Message> recent = messageRepository.findRecentBySession(
                    session.getId(), SUMMARY_INTERVAL * 2);

            String summaryPrompt = buildSummaryPrompt(oldSummary, recent);
            String newSummary = callLLMForSummary(summaryPrompt);

            if (newSummary != null && !newSummary.isEmpty()) {
                session.setSummary(newSummary);
                sessionRepository.save(session);
                logger.info("会话摘要已更新: sessionId={}, turnCount={}, 摘要长度={}",
                        session.getId(), session.getTurnCount(), newSummary.length());
            }
        } catch (Exception e) {
            logger.warn("生成摘要失败，跳过: {}", e.getMessage());
        }
    }

    private String buildSummaryPrompt(String oldSummary, List<Message> recentMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是已有对话摘要和最新的5轮对话：\n\n");

        sb.append("【历史摘要】\n");
        sb.append(oldSummary.isEmpty() ? "（无）" : oldSummary);
        sb.append("\n\n");

        sb.append("【最新对话】\n");
        for (Message msg : recentMessages) {
            String roleLabel = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append(roleLabel).append(": ").append(msg.getContent()).append("\n");
        }
        sb.append("\n");

        sb.append("请合并为一段新的摘要，保留：\n");
        sb.append("1. 面试进度和当前阶段\n");
        sb.append("2. 考察过的技术点及候选人表现（评分/等级）\n");
        sb.append("3. 未解决的问题\n");
        sb.append("4. 候选人的核心技能画像\n");
        sb.append("\n控制在 200 字以内。直接输出摘要文本，不要加其他内容。");

        return sb.toString();
    }

    private String callLLMForSummary(String prompt) {
        try {
            List<com.alibaba.dashscope.common.Message> messages = new ArrayList<>();
            messages.add(com.alibaba.dashscope.common.Message.builder()
                    .role(com.alibaba.dashscope.common.Role.USER.getValue())
                    .content(prompt)
                    .build());

            com.alibaba.dashscope.aigc.generation.Generation generation =
                    new com.alibaba.dashscope.aigc.generation.Generation();
            com.alibaba.dashscope.aigc.generation.GenerationParam param =
                    com.alibaba.dashscope.aigc.generation.GenerationParam.builder()
                            .apiKey(dashScopeApiKey)
                            .model(modelName)
                            .messages(messages)
                            .temperature(0.3f)
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

    // ==================== 内部辅助 ====================

    private User getDefaultUser() {
        return userRepository.findById(DEFAULT_USER_ID)
                .orElseThrow(() -> new IllegalStateException("默认用户不存在"));
    }

    private InterviewSession loadSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
    }
}
