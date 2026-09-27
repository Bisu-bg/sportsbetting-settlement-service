package com.sportsbetting.settlement.config;

import com.sportsbetting.settlement.SettlementApplication;
import com.sportsbetting.settlement.config.MessagingConfiguration;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementListener;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestConfiguration {
    @Test
    @SuppressWarnings("unchecked")
    void poisonMessageGoesToConfiguredDltAndFailedDltIsNotAcknowledged() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        Consumer<String, String> consumer = mock(Consumer.class);
        ConsumerGroupMetadata metadata = mock(ConsumerGroupMetadata.class);
        when(metadata.groupId()).thenReturn("test");
        when(consumer.groupMetadata()).thenReturn(metadata);
        var container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties("event-outcomes"));
        var handler = new MessagingConfiguration().kafkaErrorHandler(kafka);
        var record = new ConsumerRecord<>("event-outcomes", 1, 42L, "id", "bad");
        assertThat(handler.handleOne(new IllegalArgumentException("bad"), record, consumer, container)).isTrue();
        verify(kafka).send(argThat((ProducerRecord<String, String> sent) -> sent.topic().equals("event-outcomes.DLT")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        assertThat(handler.handleOne(new IllegalArgumentException("bad"), record, consumer, container)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuresTopicsRetriesAndClientLifecycles() throws Exception {
        var config = new MessagingConfiguration();
        assertThat(config.clock().getZone().getId()).isEqualTo("Z");
        assertThat(config.eventOutcomes().name()).isEqualTo("event-outcomes");
        assertThat(config.eventOutcomes().numPartitions()).isEqualTo(3);
        assertThat(config.deadLetterOutcomes().name()).isEqualTo("event-outcomes.DLT");
        assertThat(config.deadLetterOutcomes().numPartitions()).isEqualTo(3);
        assertThat(config.kafkaErrorHandler(mock(KafkaTemplate.class)).isAckAfterHandle()).isTrue();
        var producer = config.rocketProducer("localhost:9876");
        assertThat(producer.getNamesrvAddr()).isEqualTo("localhost:9876");
        assertThat(producer.getRetryTimesWhenSendFailed()).isEqualTo(2);
        assertThat(producer.isRetryAnotherBrokerWhenNotStoreOK()).isTrue();
        assertThat(producer.getSendMsgTimeout()).isEqualTo(5000);
        assertThat(producer.isVipChannelEnabled()).isFalse();
        var listener = mock(SettlementListener.class);
        var consumer = config.rocketConsumer("localhost:9876", listener);
        assertThat(consumer.getNamesrvAddr()).isEqualTo("localhost:9876");
        assertThat(consumer.getMaxReconsumeTimes()).isEqualTo(5);
        assertThat(consumer.getConsumeFromWhere()).isEqualTo(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(1);
        assertThat(consumer.getMessageListener()).isSameAs(listener);
        assertThat(consumer.getDefaultMQPushConsumerImpl().getRebalanceImpl().getSubscriptionInner()
                .get("bet-settlements").getSubString()).isEqualTo("*");
    }

    @Test
    void mainDelegatesToSpringBoot() {
        new SettlementApplication();
        try (var spring = mockStatic(SpringApplication.class)) {
            String[] args = {"--server.port=0"};
            SettlementApplication.main(args);
            spring.verify(() -> SpringApplication.run(SettlementApplication.class, args));
        }
    }
}
