package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.AudioTranscriptionTask;
import org.example.repository.AudioTranscriptionTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAgent {

    private final AudioTranscriptionTaskRepository taskRepository;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${rag.model:qwen-plus}")
    private String modelName;

    private final Generation generation = new Generation();

    /**
     * Generate review report for a transcription task.
     * Handles failures internally — never throws to MQ to avoid infinite retry loops.
     */
    public void generateReviewReport(String taskId) {
        AudioTranscriptionTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("复盘任务不存在，丢弃消息 - taskId: {}", taskId);
            return;
        }
        if (!"transcribed".equals(task.getStatus())) {
            log.info("任务状态非 transcribed，跳过复盘 - taskId: {}, status: {}",
                    taskId, task.getStatus());
            return;
        }

        task.setStatus("reviewing");
        task.setProgress(70);
        taskRepository.save(task);

        String transcript = task.getTranscriptText();
        if (transcript == null || transcript.isEmpty()) {
            failTask(task, "No transcript available for review");
            return;
        }

        String report;
        try {
            report = callLLM(transcript, task.getSpeakerSegments());
        } catch (Exception e) {
            failTask(task, "LLM 调用异常: " + e.getMessage());
            return;
        }

        task.setReviewReport(report);
        task.setStatus("completed");
        task.setProgress(100);
        taskRepository.save(task);

        log.info("复盘报告生成完成 - taskId: {}", taskId);
    }

    private void failTask(AudioTranscriptionTask task, String message) {
        task.setErrorMessage(message);
        task.setStatus("failed");
        taskRepository.save(task);
        log.error("复盘失败 - taskId: {}, error: {}", task.getId(), message);
    }

    private String buildReviewPrompt(String transcript, String segments) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的面试复盘专家。请根据以下面试录音的转写文本，生成结构化的面试评估报告。\n\n");
        sb.append("--- 面试转写文本 ---\n");
        sb.append(transcript).append("\n\n");
        if (segments != null && !segments.isEmpty()) {
            sb.append("--- 说话人分段 ---\n");
            sb.append(segments).append("\n\n");
        }
        sb.append("请按以下格式生成报告：\n");
        sb.append("1. 综合评级（S/A/B/C/D）\n");
        sb.append("2. 各技术点评分（1-5分制）及点评\n");
        sb.append("3. 优势总结（2-3条）\n");
        sb.append("4. 薄弱环节（2-3条）\n");
        sb.append("5. 具体改进建议（2-3条）\n\n");
        sb.append("语言简洁专业，使用中文。");
        return sb.toString();
    }

    private String callLLM(String transcript, String segments) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("你是一个专业的面试复盘专家，擅长分析候选人的技术能力和表达能力。")
                .build());
        messages.add(Message.builder()
                .role(Role.USER.getValue())
                .content(buildReviewPrompt(transcript, segments))
                .build());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(messages)
                .temperature(0.3f)
                .resultFormat("message")
                .build();

        GenerationResult result = generation.call(param);
        if (result != null && result.getOutput() != null
                && result.getOutput().getChoices() != null
                && !result.getOutput().getChoices().isEmpty()) {
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        }
        throw new RuntimeException("LLM 返回为空");
    }
}
