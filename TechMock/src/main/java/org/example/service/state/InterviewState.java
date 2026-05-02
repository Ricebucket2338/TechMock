package org.example.service.state;

import org.example.enums.InterviewPhase;

/**
 * 面试状态接口
 * 每个实现类对应 InterviewPhase 中的一个阶段，负责该阶段的业务逻辑
 * 并决定下一状态是什么
 */
public interface InterviewState {

    /**
     * 处理当前阶段逻辑
     *
     * @param ctx 上下文，携带 session、用户输入、行为分析等数据
     * @return 处理结果，包含下一阶段的 Phase 和返回给前端的文本
     */
    HandleResult handle(InterviewContext ctx);

    /**
     * 返回该状态对应的 Phase 枚举值
     */
    InterviewPhase phase();

    /**
     * 状态处理结果
     */
    class HandleResult {
        private final InterviewPhase nextPhase;
        private final String response;

        public HandleResult(InterviewPhase nextPhase, String response) {
            this.nextPhase = nextPhase;
            this.response = response;
        }

        public InterviewPhase getNextPhase() {
            return nextPhase;
        }

        public String getResponse() {
            return response;
        }
    }
}
