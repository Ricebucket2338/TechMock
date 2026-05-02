package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class SessionDetailVO {

    private String sessionId;
    private String mode;
    private String status;
    private String summary;
    private int turnCount;
    private String targetPosition;
    private String overallScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<MessageVO> messages;

    @Getter
    @Setter
    public static class MessageVO {
        private Long id;
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }
}
