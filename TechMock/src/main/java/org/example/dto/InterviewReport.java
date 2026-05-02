package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试报告 DTO
 */
@Getter
@Setter
public class InterviewReport {

    private String sessionId;
    private String interviewType;
    private String targetPosition;
    private int totalDurationSec;
    private int totalQuestions;
    private int totalFollowUps;
    private String overallGrade;
    private String overallRecommendation;

    private List<SkillAssessment> skillAssessments = new ArrayList<>();

    private String strengths;
    private String weaknesses;
    private String improvementSuggestions;

    public void addSkillAssessment(SkillAssessment assessment) {
        this.skillAssessments.add(assessment);
    }
}
