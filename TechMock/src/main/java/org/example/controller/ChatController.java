package org.example.controller;

import lombok.Getter;
import lombok.Setter;
import org.example.dto.SessionDetailVO;
import org.example.entity.InterviewSession;
import org.example.service.ChatService;
import org.example.service.ConversationService;
import org.example.service.InterviewAgent;
import org.example.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private RagService ragService;

    @Autowired
    private InterviewAgent interviewAgent;

    @Autowired
    private org.example.repository.SessionRepository sessionRepository;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    
    // 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对）
    private static final int MAX_WINDOW_SIZE = 6;

    /**
     * RAG 对话接口（非流式模式）
     * 管道：意图识别 → 查询扩展 → 混合检索 → 重排 → 生成
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到 RAG 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            SessionInfo session = getOrCreateSession(request.getId());
            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            String fullAnswer = ragService.query(request.getQuestion(), history);

            session.addMessage(request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                request.getId(), session.getMessagePairCount());

            if (request.getId() != null) {
                try {
                    conversationService.saveUserMessage(request.getId(), request.getQuestion());
                    conversationService.saveAssistantMessage(request.getId(), fullAnswer);
                    conversationService.incrementTurnAndMaybeSummarize(request.getId());
                } catch (Exception ex) {
                    logger.warn("持久化消息失败，不影响返回: {}", ex.getMessage());
                }
            }

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            SessionInfo session = sessions.get(request.getId());
            if (session != null) {
                session.clearHistory();
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * RAG 对话接口（SSE 流式模式，支持多轮对话）
     * 管道：意图识别 → 查询扩展 → 混合检索 → 重排 → 生成
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 RAG 流式对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                SessionInfo session = getOrCreateSession(request.getId());
                List<Map<String, String>> history = session.getHistory();

                final StringBuilder fullAnswerBuilder = new StringBuilder();

                ragService.queryStream(request.getQuestion(), history, new RagService.StreamCallback() {
                    @Override
                    public void onSearchResults(List<org.example.service.HybridSearchService.HybridSearchResult> results) {
                        // Silent: not sent to frontend, already embedded in generated answer
                    }

                    @Override
                    public void onReasoningChunk(String chunk) {
                        // Silent: internal reasoning not exposed
                    }

                    @Override
                    public void onContentChunk(String chunk) {
                        try {
                            fullAnswerBuilder.append(chunk);
                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                        } catch (IOException e) {
                            logger.error("发送流式内容失败", e);
                        }
                    }

                    @Override
                    public void onComplete(String fullContent, String fullReasoning) {
                        try {
                            logger.info("RAG 流式对话完成 - SessionId: {}, 答案长度: {}",
                                    request.getId(), fullContent.length());

                            session.addMessage(request.getQuestion(), fullContent);

                            if (request.getId() != null) {
                                try {
                                    conversationService.saveUserMessage(request.getId(), request.getQuestion());
                                    conversationService.saveAssistantMessage(request.getId(), fullContent);
                                    conversationService.incrementTurnAndMaybeSummarize(request.getId());
                                } catch (Exception ex) {
                                    logger.warn("持久化消息失败，不影响返回: {}", ex.getMessage());
                                }
                            }

                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        logger.error("RAG 查询出错", e);
                        try {
                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(e);
                    }
                });

            } catch (Exception e) {
                logger.error("RAG 流式对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionInfo session = sessions.get(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 历史会话查询接口 (MySQL) ====================

    /**
     * 列出用户所有历史会话
     */
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<SessionBriefVO>>> listSessions() {
        try {
            List<InterviewSession> sessionList = conversationService.listSessions("user-001");
            List<SessionBriefVO> result = new ArrayList<>();
            for (InterviewSession s : sessionList) {
                SessionBriefVO vo = new SessionBriefVO();
                vo.setSessionId(s.getId());
                vo.setMode(s.getMode());
                vo.setStatus(s.getStatus());
                vo.setTurnCount(s.getTurnCount());
                vo.setTargetPosition(s.getTargetPosition());
                vo.setOverallScore(s.getOverallScore());
                vo.setCreatedAt(s.getCreatedAt());
                vo.setUpdatedAt(s.getUpdatedAt());
                result.add(vo);
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            logger.error("列出会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取会话详情（含完整消息列表）
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<SessionDetailVO>> getSessionDetail(@PathVariable String sessionId) {
        try {
            SessionDetailVO detail = conversationService.getSessionDetail(sessionId);
            return ResponseEntity.ok(ApiResponse.success(detail));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error("会话不存在"));
        } catch (Exception e) {
            logger.error("获取会话详情失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 删除历史会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<String>> deleteSession(@PathVariable String sessionId) {
        try {
            conversationService.deleteSession(sessionId);
            return ResponseEntity.ok(ApiResponse.success("会话已删除"));
        } catch (Exception e) {
            logger.error("删除会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionCreateResponse>> createSession(@RequestBody SessionCreateRequest request) {
        try {
            String mode = request.getMode() != null ? request.getMode() : "qa";
            InterviewSession session = conversationService.createSession(mode);
            SessionCreateResponse response = new SessionCreateResponse();
            response.setSessionId(session.getId());
            response.setMode(session.getMode());
            response.setCreatedAt(session.getCreatedAt());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("创建会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 面试接口 ====================

    /**
     * 开始面试
     * POST /api/interview/start
     */
    @PostMapping("/interview/start")
    public ResponseEntity<ApiResponse<InterviewStartResponse>> startInterview(
            @RequestBody InterviewStartRequest request) {
        try {
            logger.info("收到面试开始请求 - 类型: {}", request.getInterviewType());

            InterviewAgent.InterviewStartResponse result = interviewAgent.startInterview(
                    "user-001",
                    request.getInterviewType(),
                    request.getSelectedSkills()
            );

            InterviewStartResponse response = new InterviewStartResponse();
            response.setSessionId(result.getSessionId());
            response.setOpeningMessage(result.getOpeningMessage());
            response.setPhase(result.getPhase());

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("面试启动失败", e);
            return ResponseEntity.ok(ApiResponse.error("面试启动失败: " + e.getMessage()));
        }
    }

    /**
     * 面试中回答下一题
     * POST /api/interview/answer
     */
    @PostMapping("/interview/answer")
    public ResponseEntity<ApiResponse<InterviewAnswerResponse>> interviewAnswer(
            @RequestBody InterviewAnswerRequest request) {
        try {
            logger.info("收到面试回答 - SessionId: {}", request.getSessionId());

            String result = interviewAgent.handleUserAnswer(
                    request.getSessionId(),
                    request.getAnswer()
            );

            // 获取最新阶段
            var session = sessionRepository.findById(request.getSessionId()).orElse(null);
            String currentPhase = session != null ? session.getPhase() : "UNKNOWN";

            InterviewAnswerResponse response = new InterviewAnswerResponse();

            // 解析特殊前缀
            if (result.startsWith("TERMINATE:")) {
                // 行为终止
                int sep = result.indexOf(":::");
                String reason = result.substring(10, sep);
                String reportJson = result.substring(sep + 3);
                response.setNextQuestion(null);
                response.setTerminated(true);
                response.setTerminateReason(reason);
                response.setReportData(reportJson);
                response.setCurrentPhase("已终止");
            } else if (result.startsWith("REPORT:")) {
                // 正常结束
                String reportJson = result.substring(7);
                response.setNextQuestion(null);
                response.setCompleted(true);
                response.setReportData(reportJson);
                response.setCurrentPhase("已完成");
            } else {
                response.setNextQuestion(result);
                if (session != null) {
                    org.example.enums.InterviewPhase phase = org.example.enums.InterviewPhase.valueOf(currentPhase);
                    response.setCurrentPhase(phase.getDisplayName());
                }
            }

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("面试回答处理失败", e);
            return ResponseEntity.ok(ApiResponse.error("处理失败: " + e.getMessage()));
        }
    }

    /**
     * 结束面试，获取报告
     * POST /api/interview/end
     */
    @PostMapping("/interview/end")
    public ResponseEntity<ApiResponse<InterviewReportResponse>> endInterview(
            @RequestBody InterviewEndRequest request) {
        try {
            logger.info("收到面试结束请求 - SessionId: {}", request.getSessionId());

            var report = interviewAgent.endInterview(request.getSessionId());

            InterviewReportResponse response = new InterviewReportResponse();
            response.setSessionId(report.getSessionId());
            response.setInterviewType(report.getInterviewType());
            response.setTargetPosition(report.getTargetPosition());
            response.setOverallRecommendation(report.getOverallRecommendation());

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("面试报告生成失败", e);
            return ResponseEntity.ok(ApiResponse.error("报告生成失败: " + e.getMessage()));
        }
    }

    /**
     * 查询面试报告（JSON 格式）
     * GET /api/interview/report/{sessionId}
     */
    @GetMapping("/interview/report/{sessionId}")
    public ResponseEntity<ApiResponse<String>> getInterviewReport(@PathVariable String sessionId) {
        try {
            logger.info("收到面试报告查询请求 - SessionId: {}", sessionId);
            String reportJson = interviewAgent.getReport(sessionId);
            return ResponseEntity.ok(ApiResponse.success(reportJson));
        } catch (Exception e) {
            logger.error("查询面试报告失败", e);
            return ResponseEntity.ok(ApiResponse.error("查询失败: " + e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }

    // ==================== 面试内部类 ====================

    /**
     * 开始面试请求
     */
    @Getter
    @Setter
    public static class InterviewStartRequest {
        private String interviewType;
        private List<String> selectedSkills;
    }

    /**
     * 开始面试响应
     */
    @Getter
    @Setter
    public static class InterviewStartResponse {
        private String sessionId;
        private String openingMessage;
        private String phase;
        private List<String> directionNames;
        private String currentDirection;
    }

    /**
     * 面试回答请求
     */
    @Getter
    @Setter
    public static class InterviewAnswerRequest {
        private String sessionId;
        private String answer;
    }

    /**
     * 面试回答响应
     */
    @Getter
    @Setter
    public static class InterviewAnswerResponse {
        private String nextQuestion;
        /** 面试是否已完成 */
        private boolean completed;
        /** 面试是否被行为终止 */
        private boolean terminated;
        /** 终止原因 */
        private String terminateReason;
        /** 结构化报告 JSON */
        private String reportData;
        /** 当前面试阶段，如 "问答"、"收尾"、"已完成" */
        private String currentPhase;
    }

    /**
     * 结束面试请求
     */
    @Getter
    @Setter
    public static class InterviewEndRequest {
        private String sessionId;
    }

    /**
     * 面试报告响应
     */
    @Getter
    @Setter
    public static class InterviewReportResponse {
        private String sessionId;
        private String interviewType;
        private String targetPosition;
        private String overallRecommendation;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息
     * 管理单个会话的历史消息，支持自动清理和线程安全
     */
    private static class SessionInfo {
        private final String sessionId;
        // 存储历史消息对：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 添加一对消息（用户问题 + AI回复）
         * 自动管理历史消息窗口大小
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                // 添加用户消息
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                // 添加AI回复
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                // 自动清理：保持最多 MAX_WINDOW_SIZE 对消息
                // 每对消息包含2条记录（user + assistant）
                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    // 成对删除最旧的消息（删除前2条）
                    messageHistory.remove(0); // 删除最旧的用户消息
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0); // 删除对应的AI回复
                    }
                }

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}", 
                    sessionId, messageHistory.size() / 2);

            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取历史消息（线程安全）
         * 返回副本以避免并发修改
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空历史消息
         */
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取当前消息对数
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }

    /**
     * 历史会话简要信息
     */
    @Getter
    @Setter
    public static class SessionBriefVO {
        private String sessionId;
        private String mode;
        private String status;
        private int turnCount;
        private String targetPosition;
        private String overallScore;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * 创建会话请求
     */
    @Getter
    @Setter
    public static class SessionCreateRequest {
        private String mode;
    }

    /**
     * 创建会话响应
     */
    @Getter
    @Setter
    public static class SessionCreateResponse {
        private String sessionId;
        private String mode;
        private LocalDateTime createdAt;
    }
}
