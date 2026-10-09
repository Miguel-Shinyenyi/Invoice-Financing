package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.events.SettlementConfirmedEvent;
import com.settlementengine.core.events.SettlementRequestedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes a settlement.confirmed event before its settlement.requested through the real Kafka producer, then reads
 * the read model after each is consumed. The synthetic read-model row is removed afterwards so the write-side/read-side
 * counts stay honest.
 */
@LabComponent
public class LabOutOfOrderService {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LabKafkaService kafkaView;

    public LabOutOfOrderService(KafkaTemplate<String, String> kafka, ObjectMapper mapper, JdbcTemplate jdbc,
                                LabKafkaService kafkaView) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.kafkaView = kafkaView;
    }

    public Map<String, Object> run() throws Exception {
        UUID settlementId = UUID.randomUUID();
        UUID source = LabLoadScope.POOL.get(0);
        UUID destination = LabLoadScope.POOL.get(1);
        BigDecimal amount = new BigDecimal("1.00");
        Instant confirmedAt = Instant.now();
        Instant requestedAt = confirmedAt.minusSeconds(5); // the earlier event, delivered later

        try {
            send(KafkaTopics.SETTLEMENT_CONFIRMED, settlementId,
                    new SettlementConfirmedEvent(settlementId, source, destination, amount, "USD", confirmedAt));
            String afterFirst = awaitStatus(settlementId, null);

            send(KafkaTopics.SETTLEMENT_REQUESTED, settlementId,
                    new SettlementRequestedEvent(settlementId, source, destination, amount, "USD", requestedAt));
            awaitGroupCaughtUp();
            String afterSecond = status(settlementId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("settlementId", settlementId);
            out.put("published", List.of(
                    Map.of("order", 1, "topic", KafkaTopics.SETTLEMENT_CONFIRMED, "occurredAt", confirmedAt),
                    Map.of("order", 2, "topic", KafkaTopics.SETTLEMENT_REQUESTED, "occurredAt", requestedAt)));
            out.put("afterFirstEvent", afterFirst);
            out.put("afterSecondEvent", afterSecond);
            out.put("lastWriteWinsHeld", "CONFIRMED".equals(afterFirst) && "CONFIRMED".equals(afterSecond));
            out.put("explanation", "Kafka orders messages only within one partition, never across topics, so a consumer can see "
                    + "CONFIRMED before REQUESTED. SettlementEventConsumer ignores any event whose occurredAt is older than the "
                    + "row already stored, so the late REQUESTED event does not clobber the later CONFIRMED status.");
            return out;
        } finally {
            jdbc.update("delete from settlement_read_model where settlement_id = ?", settlementId);
        }
    }

    private void send(String topic, UUID key, Object event) throws Exception {
        kafka.send(topic, key.toString(), mapper.writeValueAsString(event)).get();
    }

    private String awaitStatus(UUID id, String ignored) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String s = status(id);
            if (s != null) {
                return s;
            }
            Thread.sleep(150);
        }
        return null;
    }

    private void awaitGroupCaughtUp() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        Thread.sleep(300);
        while (System.nanoTime() < deadline) {
            var group = kafkaView.overview().groups().stream()
                    .filter(g -> g.group().equals("settlement-engine-read-model")).findFirst();
            if (group.isPresent() && group.get().totalLag() == 0) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private String status(UUID id) {
        List<String> rows = jdbc.queryForList("select status from settlement_read_model where settlement_id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
