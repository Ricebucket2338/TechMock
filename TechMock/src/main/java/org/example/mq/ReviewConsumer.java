package org.example.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.example.service.ReviewAgent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = MQConstants.TOPIC,
        selectorExpression = MQConstants.TAG_REVIEW,
        consumerGroup = MQConstants.GROUP_REVIEW,
        messageModel = MessageModel.CLUSTERING
)
@RequiredArgsConstructor
public class ReviewConsumer implements RocketMQListener<String> {

    private final ReviewAgent reviewAgent;

    @Override
    public void onMessage(String taskId) {
        log.info("收到复盘消息 - taskId: {}", taskId);
        reviewAgent.generateReviewReport(taskId);
    }
}
