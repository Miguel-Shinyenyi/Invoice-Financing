package com.settlementengine.core.lab;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@LabComponent
public class LabEventsService {

    static final String GAP_6 = "No consumer. Nothing alerts a human about a mismatch (Known gap 6).";

    private final JdbcTemplate jdbc;
    private final LabKafkaService kafka;
    private final LabMetricsService metrics;

    public LabEventsService(JdbcTemplate jdbc, LabKafkaService kafka, LabMetricsService metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.metrics = metrics;
    }

    public Map<String, Object> events(int limit) {
        int capped = Math.max(1, Math.min(100, limit));
        Map<String, Map<String, Object>> byTopic = new LinkedHashMap<>();
        for (String topic : LabTopics.all()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("topic", topic);
            t.put("pending", 0L);
            t.put("published", 0L);
            byTopic.put(topic, t);
        }
        jdbc.query("select topic, count(*) filter (where published_at is null) as pending, "
                + "count(*) filter (where published_at is not null) as published from outbox_events group by topic", rs -> {
            Map<String, Object> t = byTopic.get(rs.getString("topic"));
            if (t != null) {
                t.put("pending", rs.getLong("pending"));
                t.put("published", rs.getLong("published"));
            }
        });

        LabKafkaService.Overview overview = kafka.overview();
        for (LabKafkaService.TopicInfo info : overview.topics()) {
            Map<String, Object> t = byTopic.get(info.topic());
            t.put("consumers", overview.available() ? info.consumers() : null);
            t.put("consumerGroups", info.consumerGroups());
            t.put("kafkaMessageCount", overview.available() ? info.messageCount() : null);
        }
        Map<String, Object> mismatch = byTopic.get("reconciliation.mismatch_found");
        if (mismatch != null) {
            mismatch.put("knownGap", GAP_6);
        }
        Map<String, Object> resolved = byTopic.get("reconciliation.resolved");
        if (resolved != null) {
            resolved.put("knownGap", "No consumer yet (docs/kafka-events.md).");
        }

        List<Map<String, Object>> recent = jdbc.query("""
                select id, topic, aggregate_type, aggregate_id, created_at, published_at from outbox_events
                order by created_at desc limit ?""", (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getObject("id"));
            r.put("topic", rs.getString("topic"));
            r.put("aggregateType", rs.getString("aggregate_type"));
            r.put("aggregateId", rs.getObject("aggregate_id"));
            r.put("createdAt", rs.getTimestamp("created_at").toInstant());
            var published = rs.getTimestamp("published_at");
            r.put("publishedAt", published == null ? null : published.toInstant());
            r.put("publishLagMs", published == null ? null : published.getTime() - rs.getTimestamp("created_at").getTime());
            return r;
        }, capped);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topics", new ArrayList<>(byTopic.values()));
        out.put("recent", recent);
        out.put("readModel", metrics.readModelCounts());
        out.put("kafkaAvailable", overview.available());
        return out;
    }
}
