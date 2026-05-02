package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 意图识别结果
 */
@Getter
@Setter
public class IntentResult {

    /**
     * 意图类型
     */
    private IntentType type;

    /**
     * 置信度 0.0-1.0
     */
    private double confidence;

    public IntentResult(IntentType type, double confidence) {
        this.type = type;
        this.confidence = confidence;
    }

    public IntentResult() {
    }

    public boolean isTechQuestion() {
        return type == IntentType.TECH_QUESTION;
    }

    public boolean isClarify() {
        return type == IntentType.CLARIFY;
    }

    public boolean isChitchat() {
        return type == IntentType.CHITCHAT;
    }

    public boolean isInterviewStart() {
        return type == IntentType.INTERVIEW_START;
    }

    public enum IntentType {
        TECH_QUESTION,
        CLARIFY,
        CHITCHAT,
        INTERVIEW_START
    }
}
