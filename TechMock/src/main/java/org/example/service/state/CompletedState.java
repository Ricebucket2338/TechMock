package org.example.service.state;

import org.example.enums.InterviewPhase;

/**
 * 完成状态（终态）
 * 不再执行任何业务逻辑，仅返回已生成的报告
 */
public class CompletedState implements InterviewState {

    @Override
    public HandleResult handle(InterviewContext ctx) {
        // 终态，不产生新操作，直接返回已有报告
        String report = ctx.getSession().getReportData();
        if (report == null || report.isEmpty()) {
            report = "{\"overallGrade\":\"D\",\"strengths\":[],\"weaknesses\":[],\"suggestions\":[],\"skillAssessments\":[]}";
        }
        return new HandleResult(InterviewPhase.COMPLETED, report);
    }

    @Override
    public InterviewPhase phase() {
        return InterviewPhase.COMPLETED;
    }
}
