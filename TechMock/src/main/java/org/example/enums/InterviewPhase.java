package org.example.enums;

/**
 * 面试阶段枚举
 * 定义了模拟面试从开始到结束的完整生命周期
 */
public enum InterviewPhase {

    /**
     * 开场阶段
     * 职责：随机选择考察方向 + 初始化考点池 + 生成第一个面试问题
     * 转换：→ QUESTIONING（成功）
     */
    OPENING("开场"),

    /**
     * 问答循环阶段
     * 职责：接收候选人回答 → 行为分析 → 检查终止条件 → 检查轮数 → 管理方向切换 → 生成下一题
     * 转换：→ QUESTIONING（继续问答） | → WRAP_UP（达到终止条件） | → TERMINATED（行为违规）
     */
    QUESTIONING("问答"),

    /**
     * 收尾阶段
     * 职责：拉取全部对话记录 + 内部评估 → 调用 LLM 生成结构化报告
     * 转换：→ COMPLETED（报告生成完毕）
     */
    WRAP_UP("收尾"),

    /**
     * 异常终止状态
     * 触发条件：候选人出现攻击性言论 ≥ 2 次 或 拒绝配合 ≥ 3 次
     * 行为：生成报告并标记为强制终止，前端展示警告
     */
    TERMINATED("已终止"),

    /**
     * 完成状态（终态）
     * 触发条件：正常问答达到轮数上限 / 所有方向考察完毕 / 用户主动结束
     * 行为：报告已就绪，前端展示完整面试评估
     */
    COMPLETED("已完成");

    /** 显示名称，用于前端 UI 展示 */
    private final String displayName;

    InterviewPhase(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 是否为终态（不再接收用户输入） */
    public boolean isTerminal() {
        return this == COMPLETED || this == TERMINATED;
    }
}
