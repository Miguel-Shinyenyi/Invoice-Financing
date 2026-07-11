package com.settlementengine.core.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.events.SettlementRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxWriter = new OutboxWriter(outboxEventRepository, objectMapper);
    }

    @Test
    void writeSavesEventWithSerializedPayload() {
        UUID settlementId = UUID.randomUUID();
        SettlementRequestedEvent event = new SettlementRequestedEvent(settlementId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.00"), "USD", Instant.now());

        outboxWriter.write("SETTLEMENT", settlementId, KafkaTopics.SETTLEMENT_REQUESTED, event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getAggregateType()).isEqualTo("SETTLEMENT");
        assertThat(saved.getAggregateId()).isEqualTo(settlementId);
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.SETTLEMENT_REQUESTED);
        assertThat(saved.getPayload()).contains(settlementId.toString());
        assertThat(saved.getPublishedAt()).isNull();
    }
}
