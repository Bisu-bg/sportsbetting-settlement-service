package com.sportsbetting.settlement.messaging;

import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.messaging.BrokerUnavailableException;
import com.sportsbetting.settlement.messaging.JsonMessages;
import com.sportsbetting.settlement.domain.outcome.messaging.OutcomeListener;
import com.sportsbetting.settlement.domain.outcome.messaging.OutcomePublisher;
import com.sportsbetting.settlement.domain.settlement.messaging.RocketSettlementPublisher;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementListener;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementMessage;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeService;
import com.sportsbetting.settlement.domain.settlement.service.SettlementService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestMessaging {
    private final EventOutcome outcome = new EventOutcome("event-1", "Final", "a");
    private final SettlementMessage instruction = new SettlementMessage("bet", "event-1", "a");

    @Test
    void jsonValidatesIncomingMessagesAndReportsCodecFailures() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var json = new JsonMessages(new JsonMapper(), factory.getValidator());
            assertThat(json.read(json.write(outcome), EventOutcome.class)).isEqualTo(outcome);
            assertThatThrownBy(() -> json.read("null", EventOutcome.class)).hasMessage("Invalid message fields");
            assertThatThrownBy(() -> json.read("{}", EventOutcome.class)).hasMessage("Invalid message fields");
            assertThatThrownBy(() -> json.read("bad", EventOutcome.class)).hasMessage("Malformed message JSON");
            var mapper = mock(JsonMapper.class);
            when(mapper.writeValueAsString(any())).thenThrow(new JacksonException("broken") {});
            assertThatThrownBy(() -> new JsonMessages(mapper, factory.getValidator()).write(outcome))
                    .hasMessage("Cannot encode message");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaPublicationWaitsForAckAndPreservesInterrupt() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var json = mock(JsonMessages.class);
        when(json.write(outcome)).thenReturn("json");
        var publisher = new OutcomePublisher(kafka, json);
        when(kafka.send("event-outcomes", "event-1", "json")).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish(outcome);
        var failed = new CompletableFuture<SendResult<String, String>>();
        failed.completeExceptionally(new IllegalStateException("offline"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        assertThatThrownBy(() -> publisher.publish(outcome)).isInstanceOf(BrokerUnavailableException.class);
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(future.get(10, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        assertThatThrownBy(() -> publisher.publish(outcome)).isInstanceOf(BrokerUnavailableException.class);
        doThrow(new InterruptedException()).when(future).get(10, TimeUnit.SECONDS);
        try {
            assertThatThrownBy(() -> publisher.publish(outcome)).isInstanceOf(BrokerUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());
        assertThatThrownBy(() -> publisher.publish(outcome)).isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    void kafkaListenerLetsFailuresReachContainerRetryHandler() {
        var json = mock(JsonMessages.class);
        var outcomeService = mock(OutcomeService.class);
        when(json.read("json", EventOutcome.class)).thenReturn(outcome);
        var listener = new OutcomeListener(json, outcomeService);
        listener.consume("json");
        verify(outcomeService).handle(outcome);
        doThrow(new IllegalStateException("database offline")).when(outcomeService).handle(outcome);
        assertThatThrownBy(() -> listener.consume("json")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rocketPublisherChecksStatusAndPropagatesFailure() throws Exception {
        var producer = mock(DefaultMQProducer.class);
        var json = mock(JsonMessages.class);
        when(json.write(instruction)).thenReturn("json");
        var publisher = new RocketSettlementPublisher(producer, json);
        var result = new org.apache.rocketmq.client.producer.SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(result);
        publisher.send(instruction);
        verify(producer).send(argThat((Message message) -> message.getTopic().equals("bet-settlements")
                && message.getKeys().equals("bet")
                && new String(message.getBody(), StandardCharsets.UTF_8).equals("json")));
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        assertThatThrownBy(() -> publisher.send(instruction)).isInstanceOf(BrokerUnavailableException.class);
        when(producer.send(any(Message.class))).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> publisher.send(instruction)).isInstanceOf(BrokerUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        when(producer.send(any(Message.class))).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> publisher.send(instruction)).isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    void rocketListenerAcknowledgesOnlySuccessfulTransactions() {
        var json = mock(JsonMessages.class);
        var settlementService = mock(SettlementService.class);
        var listener = new SettlementListener(json, settlementService);
        var message = new MessageExt();
        message.setBody("json".getBytes(StandardCharsets.UTF_8));
        when(json.read("json", SettlementMessage.class)).thenReturn(instruction);
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(settlementService).settle(instruction);
        doThrow(new IllegalStateException("rollback")).when(settlementService).settle(instruction);
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        when(json.read("json", SettlementMessage.class)).thenThrow(new IllegalArgumentException("invalid JSON"));
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }
}
