package org.example.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.example.entity.AudioTranscriptionTask;
import org.example.repository.AudioTranscriptionTaskRepository;
import org.example.service.AudioTranscriptionService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = MQConstants.TOPIC,
        selectorExpression = MQConstants.TAG_TRANSCRIBE,
        consumerGroup = MQConstants.GROUP_TRANSCRIBE,
        messageModel = MessageModel.CLUSTERING
)
@RequiredArgsConstructor
public class TranscriptionConsumer implements RocketMQListener<String> {

    private final AudioTranscriptionService transcriptionService;
    private final AudioTranscriptionTaskRepository taskRepository;

    @Override
    public void onMessage(String taskId) {
        log.info("收到转写消息 - taskId: {}", taskId);
        AudioTranscriptionTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("转写任务不存在，丢弃消息 - taskId: {}", taskId);
            return;
        }
        // Only process if still in queued state (idempotency guard)
        if (!"queued".equals(task.getStatus())) {
            log.info("任务已处理，跳过 - taskId: {}, status: {}", taskId, task.getStatus());
            return;
        }
        transcriptionService.transcribe(task);
    }
}
