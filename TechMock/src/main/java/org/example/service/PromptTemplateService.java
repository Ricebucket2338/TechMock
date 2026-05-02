package org.example.service;

import org.springframework.stereotype.Service;

/**
 * 面试 Prompt 模板服务
 * 所有 prompt 要求 LLM 输出 JSON 格式：{ "analysis": {...}, "question": "..." }
 */
@Service
public class PromptTemplateService {

    private static final String OUTPUT_FORMAT =
            "\n\n【输出格式要求】\n" +
            "必须严格输出以下 JSON 格式，不要输出任何其他内容：\n" +
            "{\n" +
            "  \"analysis\": {\n" +
            "    \"evaluation\": \"对候选人回答的简要评估（1-2 句话，内部记录用）\",\n" +
            "    \"score\": 1-5 的整数评分,\n" +
            "    \"coveredTopic\": \"本轮考察的技术话题，格式为 '方向名-考点名'，如 'Redis-持久化'\"\n" +
            "  },\n" +
            "  \"question\": \"只包含问题本身的纯文本，口语化，像真人面试官说话\"\n" +
            "}\n" +
            "注意：\n" +
            "- question 字段只能包含问题，不能包含任何评价、分析、总结内容\n" +
            "- question 中不得出现\"考点\"、\"方向\"、\"当前考察\"、\"下一个\"等暴露面试机制的词\n" +
            "- 像真人面试官一样自然过渡话题，不要提及\"考点切换\"之类的内部逻辑";

    // ==================== 技术面（无简历）- 开场 ====================

    public String buildTechFundamentalsPrompt(java.util.List<String> directionNames,
                                               String firstDirection, java.util.List<String> firstTopics) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂技术一面面试官，对候选人进行技术面试。\n");
        sb.append("本次面试会从以下技术领域中选择若干个进行考察：\n");
        for (int i = 0; i < directionNames.size(); i++) {
            sb.append(i + 1).append(". ").append(directionNames.get(i)).append("\n");
        }
        sb.append("\n");
        sb.append("开场规则：\n");
        sb.append("- 先从「").append(firstDirection).append("」领域开始，围绕以下话题提问：").append(String.join("、", firstTopics)).append("\n");
        sb.append("- 做简短自我介绍后直接开始技术提问\n");
        sb.append("- 每个话题最多问 3 次，答不上来不要反复纠缠\n");
        sb.append("- 已聊过的话题不要再问\n");
        sb.append("\n");
        sb.append("重要提醒：\n");
        sb.append("- 不要在问题中提及\"考点\"、\"方向\"、\"当前考察\"等词\n");
        sb.append("- 像真人面试官一样自然地开始面试\n");
        sb.append("\n");
        sb.append("语气要求：口语化，像真实面试对话，不要书面语。\n");
        sb.append(OUTPUT_FORMAT);
        return sb.toString();
    }

    // ==================== 通用：技术面提问 prompt（每轮动态生成） ====================

    public String buildTechQuestionPrompt(String conversationSummary, String currentDirection,
                                           String currentTopic, String coveredTopics, String behaviorNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂技术一面面试官，正在进行技术面试。\n");
        sb.append("\n");
        sb.append("当前状态：\n");
        sb.append("- 正在考察的技术领域：").append(currentDirection != null ? currentDirection : "综合").append("\n");
        if (currentTopic != null && !currentTopic.isEmpty()) {
            sb.append("- 本轮关注话题：").append(currentTopic).append("\n");
        }
        if (coveredTopics != null && !coveredTopics.isEmpty()) {
            sb.append("- 之前已经聊过的话题：").append(coveredTopics).append("\n");
        }
        sb.append("\n");
        sb.append("提问规则：\n");
        sb.append("- 每次只问一个问题，口语化，像真人面试官\n");
        sb.append("- 根据候选人上一轮回答决定是追问还是引入新话题\n");
        sb.append("- 答得好：可以追问 1 次更深的内容，然后主动切换到新话题\n");
        sb.append("- 答得一般：换一种问法再问一次\n");
        sb.append("- 答不上来：不要纠缠，自然过渡到下一个话题\n");
        sb.append("- 同一话题最多问 2 次\n");

        if (behaviorNote != null && !behaviorNote.isEmpty()) {
            sb.append("\n[行为状态] ").append(behaviorNote).append("\n");
            sb.append("注意：候选人刚才的发言不当，请保持专业态度继续面试。\n");
        }

        sb.append("\n");
        sb.append("重要提醒：\n");
        sb.append("- 不要在问题中提及\"考点\"、\"方向\"、\"当前考察\"等词\n");
        sb.append("- 像真人面试官一样自然地过渡话题\n");

        sb.append("\n");
        sb.append("语气要求：口语化，像真实面试对话。\n");
        if (conversationSummary != null && !conversationSummary.isEmpty()) {
            sb.append("\n最近对话上下文：\n").append(conversationSummary);
        }
        sb.append(OUTPUT_FORMAT);
        return sb.toString();
    }

    // ==================== 非技术面 ====================

    public String buildNonTechIceBreakerPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂非技术面面试官，负责评估候选人综合素质。\n");
        sb.append("当前处于破冰阶段。先做简短自我介绍，然后请候选人自我介绍。\n");
        sb.append("语气友好亲和，营造良好氛围。\n");
        sb.append(OUTPUT_FORMAT);
        return sb.toString();
    }

    public String buildNonTechExperiencePrompt(String conversationSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂非技术面面试官，当前处于经历问答阶段。\n");
        sb.append("围绕工作经历和职业选择提问，关注：离职原因、面对困难的方式、团队合作角色。\n");
        sb.append("每次只问一个问题，语气口语化。\n");
        sb.append(OUTPUT_FORMAT);
        if (conversationSummary != null && !conversationSummary.isEmpty()) {
            sb.append("\n之前对话：").append(conversationSummary);
        }
        return sb.toString();
    }

    public String buildNonTechSoftSkillsPrompt(String conversationSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂非技术面面试官，当前处于软技能评估阶段。\n");
        sb.append("通过场景化问题评估：沟通表达、团队协作、抗压能力、学习能力。\n");
        sb.append("语气口语化。\n");
        sb.append(OUTPUT_FORMAT);
        if (conversationSummary != null && !conversationSummary.isEmpty()) {
            sb.append("\n之前对话：").append(conversationSummary);
        }
        return sb.toString();
    }

    public String buildNonTechWrapUpPrompt() {
        return "你是大厂非技术面面试官，当前处于面试总结阶段。基于全程表现生成面试评估报告。报告包含：综合等级（S/A/B/C/D）、沟通表达能力评分、团队协作能力评分、抗压能力评分、学习能力评分、优势、薄弱环节、改进建议。";
    }

    // ==================== 八股文阶段提问 prompt ====================

    public String buildBasicQuestionPrompt(String conversationSummary, String currentDirection,
                                            String currentPoint, String coveredTopics, String behaviorNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂技术一面面试官，正在进行技术面试。\n");
        sb.append("\n");
        sb.append("当前状态：\n");
        sb.append("- 正在考察的技术领域：").append(currentDirection != null ? currentDirection : "综合").append("\n");
        if (currentPoint != null && !currentPoint.isEmpty()) {
            sb.append("- 本轮关注话题：").append(currentPoint).append("\n");
        }
        if (coveredTopics != null && !coveredTopics.isEmpty()) {
            sb.append("- 之前已经聊过的话题：").append(coveredTopics).append("\n");
        }
        sb.append("\n");
        sb.append("提问规则：\n");
        sb.append("- 每次只问一个问题，口语化，像真人面试官\n");
        sb.append("- 根据候选人上一轮回答决定是追问更深的内容，还是引入新话题\n");
        sb.append("- 答得好：可以追问 1 次更深层的内容\n");
        sb.append("- 答得一般：换一种问法再问一次\n");
        sb.append("- 答不上来：不要纠缠，自然过渡到下一个话题\n");
        sb.append("- 同一话题最多问 3 次，问完自然切到下一个\n");

        if (behaviorNote != null && !behaviorNote.isEmpty()) {
            sb.append("\n[行为状态] ").append(behaviorNote).append("\n");
            sb.append("注意：候选人刚才的发言不当，请保持专业态度继续面试。\n");
        }

        sb.append("\n");
        sb.append("重要提醒：\n");
        sb.append("- 不要在问题中提及\"考点\"、\"方向\"、\"当前考察\"等词\n");
        sb.append("- 不要说\"我们当前考点是xxx\"之类的话，像真人面试官那样自然地问\n");
        sb.append("- 不要说\"接下来我们聊聊xxx\"，直接从技术问题切入\n");

        sb.append("\n");
        sb.append("语气要求：口语化，像真实面试对话。\n");
        if (conversationSummary != null && !conversationSummary.isEmpty()) {
            sb.append("\n最近对话上下文：\n").append(conversationSummary);
        }
        sb.append(OUTPUT_FORMAT);
        return sb.toString();
    }

    // ==================== 场景题阶段提问 prompt ====================

    public String buildScenarioQuestionPrompt(String conversationSummary, String scenarioQuestion,
                                               String behaviorNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大厂技术一面面试官，正在进行技术面试的场景题考察阶段。\n");
        sb.append("这个阶段主要考察候选人的实际问题解决能力和技术思维。\n");
        sb.append("\n");
        sb.append("请基于以下场景向候选人提问：\n");
        sb.append("【场景】").append(scenarioQuestion).append("\n");
        sb.append("\n");
        sb.append("回答要求：\n");
        sb.append("- 将场景转化为自然的面试提问，口语化\n");
        sb.append("- 引导候选人说出解决思路、技术方案、权衡考虑\n");
        sb.append("- 如果候选人回答得好，可以追问更细节的技术实现\n");
        sb.append("- 每次只问一个问题\n");

        if (behaviorNote != null && !behaviorNote.isEmpty()) {
            sb.append("\n[行为状态] ").append(behaviorNote).append("\n");
        }

        sb.append("\n");
        sb.append("语气要求：口语化，像真实面试对话。\n");
        if (conversationSummary != null && !conversationSummary.isEmpty()) {
            sb.append("\n最近对话上下文：\n").append(conversationSummary);
        }
        sb.append(OUTPUT_FORMAT);
        return sb.toString();
    }
}
