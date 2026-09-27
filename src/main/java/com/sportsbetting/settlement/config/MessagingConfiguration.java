package com.sportsbetting.settlement.config;

import com.sportsbetting.settlement.domain.settlement.messaging.SettlementListener;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.messaging-enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfiguration {
    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    public NewTopic eventOutcomes() {
        return TopicBuilder.name("event-outcomes").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic deadLetterOutcomes() {
        return TopicBuilder.name("event-outcomes.DLT").partitions(3).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition("event-outcomes.DLT", record.partition()));
        // A failed DLT send must not turn into an acknowledged/lost source record.
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer rocketProducer(@Value("${app.rocketmq.nameserver}") String nameserver) {
        var producer = new DefaultMQProducer("bet-settlement-producer");
        producer.setNamesrvAddr(nameserver);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(5000);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setRetryAnotherBrokerWhenNotStoreOK(true);
        return producer;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer rocketConsumer(@Value("${app.rocketmq.nameserver}") String nameserver,
                                               SettlementListener settlementListener) throws MQClientException {
        var consumer = new DefaultMQPushConsumer("bet-settlement-consumer");
        consumer.setNamesrvAddr(nameserver);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(4);
        consumer.setMaxReconsumeTimes(5);
        consumer.subscribe("bet-settlements", "*");
        consumer.registerMessageListener(settlementListener);
        return consumer;
    }
}
