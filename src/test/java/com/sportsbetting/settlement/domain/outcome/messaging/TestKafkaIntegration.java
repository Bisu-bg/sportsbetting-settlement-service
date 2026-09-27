package com.sportsbetting.settlement.domain.outcome.messaging;

import com.sportsbetting.settlement.support.BetFixtures;
import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.config.MessagingConfiguration;
import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.messaging.JsonMessages;
import com.sportsbetting.settlement.domain.outcome.messaging.OutcomeListener;
import com.sportsbetting.settlement.domain.outcome.messaging.OutcomePublisher;
import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import com.sportsbetting.settlement.domain.bet.service.BetService;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeService;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import org.apache.kafka.clients.consumer.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"app.messaging-enabled=false", "spring.datasource.url=jdbc:h2:mem:kafka;DB_CLOSE_DELAY=-1"})
@EmbeddedKafka(partitions = 3, topics = {"event-outcomes", "event-outcomes.DLT"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(TestKafkaIntegration.Listeners.class)
@DirtiesContext
class TestKafkaIntegration {
    @TestConfiguration
    static class Listeners {
        @Bean
        OutcomeListener listener(JsonMessages jsonMessages, OutcomeService outcomeService) {
            return new OutcomeListener(jsonMessages, outcomeService);
        }
        @Bean
        DefaultErrorHandler errors(KafkaTemplate<String, String> kafka) {
            return new MessagingConfiguration().kafkaErrorHandler(kafka);
        }
    }

    @Autowired OutcomePublisher publisher;
    @Autowired BetService betService;
    @Autowired OutboxRepository outboxRepository;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaTemplate<String, String> kafka;

    @Test
    void realKafkaDeliveryCreatesOutboxAndMalformedPayloadReachesDlt() throws Exception {
        betService.place(BetFixtures.request("kafka-bet", "a"));
        publisher.publish(new EventOutcome("event-1", "Final", "a"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(betService.get("kafka-bet").getStatus()).isEqualTo(BetStatus.PENDING);
            assertThat(outboxRepository.findById("kafka-bet")).isPresent();
        });
        publisher.publish(new EventOutcome("event-1", "Final", "a"));
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            broker.consumeFromAnEmbeddedTopic(consumer, "event-outcomes.DLT");
            kafka.send("event-outcomes", "bad-event", "{not-json").get();
            var record = KafkaTestUtils.getSingleRecord(consumer, "event-outcomes.DLT", Duration.ofSeconds(30));
            assertThat(record.value()).isEqualTo("{not-json");
            assertThat(record.key()).isEqualTo("bad-event");
        }
        assertThat(outboxRepository.count()).isEqualTo(1);
    }
}
