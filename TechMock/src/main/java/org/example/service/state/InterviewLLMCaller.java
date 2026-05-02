package org.example.service.state;

import org.example.dto.InterviewTurnResult;

/**
 * LLM 调用器接口
 * InterviewAgent 实现此接口，供所有 State 类调用
 */
public interface InterviewLLMCaller {

    /** 调用 LLM 生成面试问题（temperature=0.7） */
    InterviewTurnResult call(String systemPrompt, String userMessage);

    /** 调用 LLM 生成最终报告（temperature=0.3） */
    String callReport(String systemPrompt, String userMessage);

    /** 调用 LLM 做行为意图分析（temperature=0.2） */
    String callBehavior(String systemPrompt);
}
