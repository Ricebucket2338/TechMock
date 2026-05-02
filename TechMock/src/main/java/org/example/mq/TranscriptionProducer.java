package org.example.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranscriptionProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void sendTranscribe(String taskId) {
        Message<String> message = MessageBuilder.withPayload(taskId).build();
        rocketMQTemplate.syncSend(
                MQConstants.TOPIC + ":" + MQConstants.TAG_TRANSCRIBE, message);
        log.info("发送转写消息 - taskId: {}", taskId);
    }

    public void sendReview(String taskId) {
        Message<String> message = MessageBuilder.withPayload(taskId).build();
        rocketMQTemplate.syncSend(
                MQConstants.TOPIC + ":" + MQConstants.TAG_REVIEW, message);
        log.info("发送复盘消息 - taskId: {}", taskId);
    }
}
