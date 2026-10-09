package com.settlementengine.core.lab;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One real counter and last-event time per node of the system map on /lab. Everything is read from the tables the
 * engine writes (and the Kafka end offsets), so a node lights up only when something really happened there.
 */
@LabComponent
public class LabSystemService {

    private final JdbcTemplate jdbc;
    private final LabKafkaService kafka;
    private long kafkaTotal;
    private Instant kafkaAt = Instant.EPOCH;

    public LabSystemService(JdbcTemplate jdbc, LabKafkaService kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("client", "select count(*), max(created_at) from idempotency_keys"));
        nodes.add(node("idempotency", "select count(*), max(created_at) from idempotency_keys"));
        nodes.add(node("pending", "select count(*), max(created_at) from settlements"));
        nodes.add(node("gateway", "select count(*), max(updated_at) from settlements where status <> 'PENDING'"));
        nodes.add(node("finalize", "select count(*), max(created_at) from ledger_entries where entry_type <> 'OPENING'"));
        nodes.add(node("outbox", "select count(*), max(published_at) from outbox_events where published_at is not null"));
        nodes.add(kafkaNode());
        nodes.add(node("readmodel", "select count(*), max(updated_at) from settlement_read_model"));
        nodes.add(node("reconciliation", "select count(*), max(started_at) from reconciliation_runs"));
        nodes.add(node("sweep", "select count(*), max(updated_at) from settlements where status = 'UNKNOWN' and external_ref is null"));
        nodes.add(node("ledgercheck", "select (select count(*) from audit_log where action = 'GET_ACCOUNT') "
                + "+ (select count(*) from ledger_mismatches), greatest((select max(created_at) from audit_log "
                + "where action = 'GET_ACCOUNT'), (select max(created_at) from ledger_mismatches))"));
        nodes.add(node("fraud", "select count(*), max(created_at) from fraud_assessments"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", Instant.now());
        out.put("nodes", nodes);
        return out;
    }

    private Map<String, Object> node(String id, String sql) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        jdbc.query(sql, rs -> {
            n.put("count", rs.getLong(1));
            Timestamp last = rs.getTimestamp(2);
            n.put("lastEventAt", last == null ? null : last.toInstant());
        });
        return n;
    }

    private synchronized Map<String, Object> kafkaNode() {
        if (Instant.now().isAfter(kafkaAt.plusSeconds(4))) {
            LabKafkaService.Overview o = kafka.overview();
            if (o.available()) {
                kafkaTotal = o.topics().stream().mapToLong(LabKafkaService.TopicInfo::messageCount).sum();
            }
            kafkaAt = Instant.now();
        }
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "kafka");
        n.put("count", kafkaTotal);
        n.put("lastEventAt", null);
        return n;
    }
}
