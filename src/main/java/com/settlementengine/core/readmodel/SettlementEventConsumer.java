package com.settlementengine.core.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.events.SettlementConfirmedEvent;
import com.settlementengine.core.events.SettlementFailedEvent;
import com.settlementengine.core.events.SettlementRequestedEvent;
import com.settlementengine.core.events.SettlementUnknownEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class SettlementEventConsumer {

    private final SettlementReadModelRepository repository;
    private final ObjectMapper objectMapper;

    public SettlementEventConsumer(SettlementReadModelRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.SETTLEMENT_REQUESTED)
    public void onRequested(String payload) throws Exception {
        SettlementRequestedEvent event = objectMapper.readValue(payload, SettlementRequestedEvent.class);
        upsert(event.settlementId(), event.sourceAccountId(), event.destinationAccountId(), event.amount(),
                event.currency(), "PENDING", event.occurredAt());
    }

    @KafkaListener(topics = KafkaTopics.SETTLEMENT_CONFIRMED)
    public void onConfirmed(String payload) throws Exception {
        SettlementConfirmedEvent event = objectMapper.readValue(payload, SettlementConfirmedEvent.class);
        upsert(event.settlementId(), event.sourceAccountId(), event.destinationAccountId(), event.amount(),
                event.currency(), "CONFIRMED", event.occurredAt());
    }

    @KafkaListener(topics = KafkaTopics.SETTLEMENT_FAILED)
    public void onFailed(String payload) throws Exception {
        SettlementFailedEvent event = objectMapper.readValue(payload, SettlementFailedEvent.class);
        upsert(event.settlementId(), event.sourceAccountId(), event.destinationAccountId(), event.amount(),
                event.currency(), "FAILED", event.occurredAt());
    }

    @KafkaListener(topics = KafkaTopics.SETTLEMENT_UNKNOWN)
    public void onUnknown(String payload) throws Exception {
        SettlementUnknownEvent event = objectMapper.readValue(payload, SettlementUnknownEvent.class);
        upsert(event.settlementId(), event.sourceAccountId(), event.destinationAccountId(), event.amount(),
                event.currency(), "UNKNOWN", event.occurredAt());
    }

    /**
     * Kafka only orders messages within a single topic-partition, never across topics. Since each
     * settlement's requested/confirmed/failed/unknown events land on four different topics, each
     * consumed by its own listener thread, this consumer can observe them out of order (e.g.
     * CONFIRMED before REQUESTED) and, since two of those threads can race on the very first
     * upsert for a given settlement, can also hit a duplicate-key race on the initial insert (seen
     * empirically under the integration test). Both are handled here: a stale event (older
     * occurredAt than what's already stored) is ignored instead of clobbering a later status, and
     * a lost insert race falls back to re-reading and updating the row the other thread created.
     */
    private void upsert(UUID settlementId, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                         String currency, String status, Instant occurredAt) {
        SettlementReadModel model = repository.findById(settlementId).orElse(null);
        if (model == null) {
            try {
                repository.save(new SettlementReadModel(settlementId, sourceAccountId, destinationAccountId, amount,
                        currency, status, occurredAt));
                return;
            } catch (DataAccessException raceLost) {
                model = repository.findById(settlementId).orElseThrow(() -> raceLost);
            }
        }
        if (occurredAt.isBefore(model.getUpdatedAt())) {
            return;
        }
        model.applyStatus(status, occurredAt);
        repository.save(model);
    }
}
