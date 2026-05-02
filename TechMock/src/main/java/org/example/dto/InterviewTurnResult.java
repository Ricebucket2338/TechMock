package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 面试单轮 LLM 结构化输出
 * analysis 部分仅存入数据库，不展示给候选人
 * question 部分展示给候选人
 */
@Getter
@Setter
public class InterviewTurnResult {

    private Analysis analysis;
    private String question;

    @Getter
    @Setter
    public static class Analysis {
        /** 对本次回答的简要评估 */
        private String evaluation;
        /** 1-5 评分 */
        private Integer score;
        /** 本轮覆盖的技术话题，如 "MySQL-二级索引" */
        private String coveredTopic;
    }
}
