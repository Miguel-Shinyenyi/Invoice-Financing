package com.settlementengine.core.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.settlementengine.core.events.SettlementConfirmedEvent;
import com.settlementengine.core.events.SettlementFailedEvent;
import com.settlementengine.core.events.SettlementRequestedEvent;
import com.settlementengine.core.events.SettlementUnknownEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementEventConsumerTest {

    @Mock
    private SettlementReadModelRepository repository;

    private SettlementEventConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new SettlementEventConsumer(repository, objectMapper);
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void requestedCreatesNewPendingRow() throws Exception {
        UUID settlementId = UUID.randomUUID();
        SettlementRequestedEvent event = new SettlementRequestedEvent(settlementId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.00"), "USD", Instant.now());
        when(repository.findById(settlementId)).thenReturn(Optional.empty());

        consumer.onRequested(json(event));

        ArgumentCaptor<SettlementReadModel> captor = ArgumentCaptor.forClass(SettlementReadModel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSettlementId()).isEqualTo(settlementId);
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void confirmedUpdatesExistingRowStatus() throws Exception {
        UUID settlementId = UUID.randomUUID();
        SettlementReadModel existing = new SettlementReadModel(settlementId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "USD", "PENDING", Instant.now());
        when(repository.findById(settlementId)).thenReturn(Optional.of(existing));
        SettlementConfirmedEvent event = new SettlementConfirmedEvent(settlementId, existing.getSourceAccountId(),
                existing.getDestinationAccountId(), existing.getAmount(), existing.getCurrency(), Instant.now());

        consumer.onConfirmed(json(event));

        assertThat(existing.getStatus()).isEqualTo("CONFIRMED");
        verify(repository).save(existing);
    }

    @Test
    void failedUpdatesExistingRowStatus() throws Exception {
        UUID settlementId = UUID.randomUUID();
        SettlementReadModel existing = new SettlementReadModel(settlementId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "USD", "PENDING", Instant.now());
        when(repository.findById(settlementId)).thenReturn(Optional.of(existing));
        SettlementFailedEvent event = new SettlementFailedEvent(settlementId, existing.getSourceAccountId(),
                existing.getDestinationAccountId(), existing.getAmount(), existing.getCurrency(), Instant.now());

        consumer.onFailed(json(event));

        assertThat(existing.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void unknownUpdatesExistingRowStatus() throws Exception {
        UUID settlementId = UUID.randomUUID();
        SettlementReadModel existing = new SettlementReadModel(settlementId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "USD", "PENDING", Instant.now());
        when(repository.findById(settlementId)).thenReturn(Optional.of(existing));
        SettlementUnknownEvent event = new SettlementUnknownEvent(settlementId, existing.getSourceAccountId(),
                existing.getDestinationAccountId(), existing.getAmount(), existing.getCurrency(), Instant.now());

        consumer.onUnknown(json(event));

        assertThat(existing.getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void confirmedForUnknownSettlementCreatesRowDirectly() throws Exception {
        // Kafka orders messages within a topic-partition, never across topics, so CONFIRMED can
        // legitimately be consumed before REQUESTED for the same settlement.
        UUID settlementId = UUID.randomUUID();
        when(repository.findById(settlementId)).thenReturn(Optional.empty());
        SettlementConfirmedEvent event = new SettlementConfirmedEvent(settlementId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.00"), "USD", Instant.now());

        assertThatCode(() -> consumer.onConfirmed(json(event))).doesNotThrowAnyException();

        ArgumentCaptor<SettlementReadModel> captor = ArgumentCaptor.forClass(SettlementReadModel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void staleOutOfOrderRequestedEventDoesNotDowngradeALaterConfirmedStatus() throws Exception {
        UUID settlementId = UUID.randomUUID();
        Instant confirmedAt = Instant.now();
        Instant requestedAt = confirmedAt.minusSeconds(5);
        SettlementReadModel existing = new SettlementReadModel(settlementId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "USD", "CONFIRMED", confirmedAt);
        when(repository.findById(settlementId)).thenReturn(Optional.of(existing));
        SettlementRequestedEvent lateRequested = new SettlementRequestedEvent(settlementId,
                existing.getSourceAccountId(), existing.getDestinationAccountId(), existing.getAmount(),
                existing.getCurrency(), requestedAt);

        consumer.onRequested(json(lateRequested));

        assertThat(existing.getStatus()).isEqualTo("CONFIRMED");
        verify(repository, never()).save(any());
    }

    @Test
    void concurrentFirstInsertRaceFallsBackToUpdatingTheWinningRow() throws Exception {
        // Two different topics (requested vs confirmed) are consumed by two independent listener
        // threads; both can see "no row yet" for the same settlement and race to insert it.
        UUID settlementId = UUID.randomUUID();
        SettlementRequestedEvent event = new SettlementRequestedEvent(settlementId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.00"), "USD", Instant.now());
        SettlementReadModel winnersRow = new SettlementReadModel(settlementId, event.sourceAccountId(),
                event.destinationAccountId(), event.amount(), event.currency(), "CONFIRMED", Instant.now());

        when(repository.findById(settlementId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(repository).save(org.mockito.ArgumentMatchers.argThat(m -> m != winnersRow));

        consumer.onRequested(json(event));

        // The late PENDING event must not have clobbered the row the other thread already wrote.
        assertThat(winnersRow.getStatus()).isEqualTo("CONFIRMED");
    }
}
