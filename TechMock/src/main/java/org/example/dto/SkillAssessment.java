package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 技能评估 DTO
 * 记录每个技能点的评估结果
 */
@Getter
@Setter
public class SkillAssessment {

    /**
     * 技能名称
     */
    private String skillName;

    /**
     * 掌握等级 1-5
     * 1-不了解, 2-听说过, 3-基础掌握, 4-熟练应用, 5-精通
     */
    private int level;

    /**
     * 评估置信度 0.0-1.0
     */
    private double confidence;

    /**
     * 评估依据
     */
    private String evidence;
}
