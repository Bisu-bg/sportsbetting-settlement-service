package com.sportsbetting.settlement.domain.settlement.messaging;

import com.sportsbetting.settlement.domain.settlement.service.SettlementService;
import com.sportsbetting.settlement.messaging.JsonMessages;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementListener implements MessageListenerConcurrently {
    private final JsonMessages jsonMessages;
    private final SettlementService settlementService;

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        try {
            for (MessageExt message : messages) {
                settlementService.settle(jsonMessages.read(new String(message.getBody(), StandardCharsets.UTF_8), SettlementMessage.class));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (RuntimeException ex) {
            log.error("Settlement batch failed; RocketMQ will retry then dead-letter", ex);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
}
