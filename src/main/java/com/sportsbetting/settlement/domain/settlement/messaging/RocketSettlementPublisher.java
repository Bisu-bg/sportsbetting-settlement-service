package com.sportsbetting.settlement.domain.settlement.messaging;

import com.sportsbetting.settlement.messaging.BrokerUnavailableException;
import com.sportsbetting.settlement.messaging.JsonMessages;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.messaging-enabled", havingValue = "true", matchIfMissing = true)
public class RocketSettlementPublisher implements SettlementPublisher {
    private final DefaultMQProducer rocketProducer;
    private final JsonMessages jsonMessages;

    @Override
    public void send(SettlementMessage settlement) {
        var message = new Message("bet-settlements", "settle", settlement.betId(),
                jsonMessages.write(settlement).getBytes(StandardCharsets.UTF_8));
        try {
            var result = rocketProducer.send(message);
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ did not durably acknowledge: " + result.getSendStatus());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException(ex);
        } catch (Exception ex) {
            throw new BrokerUnavailableException(ex);
        }
    }
}
