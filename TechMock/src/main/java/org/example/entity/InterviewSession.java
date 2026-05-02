package org.example.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_session")
@Getter
@Setter
public class InterviewSession {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String mode;

    /**
     * 面试类型: "tech" 技术面, "nontech" 非技术面（综合素质）
     */
    @Column(length = 20)
    private String interviewType;

    @Column(nullable = false, length = 20)
    private String status = "active";

    /**
     * 当前面试阶段，对应 InterviewPhase 枚举
     */
    @Column(nullable = false, length = 20)
    private String phase = "OPENING";

    @Lob
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private int turnCount = 0;

    /**
     * 已覆盖的技术话题，逗号分隔，如 "Java基础,MySQL,Redis"
     */
    @Column(length = 512)
    private String coveredTopics;

    /**
     * JSON: 候选人表现薄弱的技术方向，如 ["JVM","MySQL"]
     * 候选人表示"不知道"的方向会被记录在此，后续不再深入考察
     */
    @Column(length = 256)
    private String weakAreas;

    @Column(length = 128)
    private String targetPosition;

    @Column(length = 10)
    private String overallScore;

    private Integer totalDurationSec;

    /**
     * JSON: {"Java基础-集合框架": 2, "MySQL-索引原理": 1}
     * 记录每个考点被问了多少次
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String topicQuestionMap;

    /**
     * 攻击性行为次数（辱骂等），累计 2 次自动终止
     */
    @Column(nullable = false)
    private int aggressiveCount = 0;

    /**
     * 不配合行为次数（回避/拒绝回答等），累计 3 次终止
     */
    @Column(nullable = false)
    private int uncooperativeCount = 0;

    /**
     * JSON: 行为警告记录列表
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String behaviorWarnings;

    /**
     * 面试开始时间
     */
    @Column
    private LocalDateTime startTime;

    /**
     * JSON: 本次面试随机选中的方向顺序，如 ["Redis","JVM","MySQL","MQ","Spring"]
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String directionOrder;

    /**
     * 当前考察方向索引
     */
    @Column(nullable = false)
    private int currentDirectionIdx = 0;

    /**
     * 当前方向名称
     */
    @Column(length = 64)
    private String currentDirection;

    /**
     * JSON: 当前方向的考点池（已打乱），如 ["持久化","缓存问题","数据类型","集群"]
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String topicPool;

    /**
     * 当前面试阶段类型: "BASIC" (八股文) / "SCENARIO" (场景题)
     */
    @Column(length = 20)
    private String currentPhaseType;

    /**
     * JSON: 八股文方向队列
     * [{"direction":"Redis","points":[{"name":"持久化机制","asked":0},{"name":"缓存雪崩","asked":0}]}, ...]
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String directionQueue;

    /**
     * JSON: 场景题队列
     * [{"question":"...","asked":0}, ...]
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String scenarioQueue;

    /**
     * JSON: 全局考点队列（轮询排列），格式：
     * [{"direction":"Java基础","topic":"集合框架","asked":0}, ...]
     * 确保每个选中的方向都被覆盖
     * @deprecated 已被 directionQueue 替代，保留兼容
     */
    @Deprecated
    @Lob
    @Column(columnDefinition = "TEXT")
    private String globalTopicQueue;

    /**
     * JSON: 结构化报告数据（S/A/B/C/D, 各技能评分, 优势, 劣势, 建议等）
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String reportData;

    /**
     * 上一轮面试官提问的考点方向，用于 KNOWLEDGE_GAP 时定位删除目标
     */
    @Column(length = 64)
    private String lastAskedDirection;

    /**
     * 上一轮面试官提问的具体考点名，用于 KNOWLEDGE_GAP 时定位删除目标
     */
    @Column(length = 128)
    private String lastAskedPoint;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
