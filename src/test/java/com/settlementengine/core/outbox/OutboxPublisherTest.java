package com.settlementengine.core.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, 100);
    }

    @Test
    void publishesUnpublishedEventsAndMarksThemPublished() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SETTLEMENT", aggregateId,
                "settlement.requested", "{\"a\":1}");
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("settlement.requested"), eq(aggregateId.toString()), eq("{\"a\":1}")))
                .thenReturn(CompletableFuture.completedFuture(mockSendResult()));

        outboxPublisher.publishPending();

        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    void leavesEventUnpublishedWhenSendFails() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SETTLEMENT", aggregateId,
                "settlement.requested", "{\"a\":1}");
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(eq("settlement.requested"), eq(aggregateId.toString()), eq("{\"a\":1}")))
                .thenReturn(failed);

        outboxPublisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        verify(outboxEventRepository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    private SendResult<String, String> mockSendResult() {
        return (SendResult<String, String>) org.mockito.Mockito.mock(SendResult.class);
    }
}
