package com.settlementengine.core.lab;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A curated snapshot read straight from the MeterRegistry, plus a few table counts. Deliberately not a proxy of
 * /actuator/prometheus (see docs/observability.md).
 */
@LabComponent
public class LabMetricsService {

    static final String PROMQL_OUTCOMES = "sum by (outcome) (settlement_outcome_total)";
    static final String PROMQL_P95 =
            "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job=\"backend\"}[5m]))";
    static final String SQL_FRAUD_SCORES = "SELECT score FROM fraud_assessments";
    static final String SQL_FRAUD_DECISIONS = "SELECT decision, count(*) as value FROM fraud_assessments GROUP BY decision";

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public LabMetricsService(MeterRegistry registry, JdbcTemplate jdbc, DataSource dataSource) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", Instant.now());

        Map<String, Double> outcomes = new LinkedHashMap<>();
        for (String o : List.of("CONFIRMED", "FAILED", "UNKNOWN")) {
            outcomes.put(o, registry.counter("settlement.outcome", "outcome", o).count());
        }
        out.put("settlementOutcome", outcomes);

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("finalizeRetries", registry.counter("settlement.finalize.retries").count());
        counters.put("unknownFallbacks", registry.counter("settlement.unknown.fallback").count());
        out.put("counters", counters);

        out.put("http", http());

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("threadsLive", gauge("jvm.threads.live"));
        jvm.put("threadsDaemon", gauge("jvm.threads.daemon"));
        jvm.put("threadsPeak", gauge("jvm.threads.peak"));
        out.put("jvm", jvm);

        Map<String, Object> hikari = new LinkedHashMap<>();
        HikariPoolMXBean pool = pool();
        hikari.put("active", pool == null ? null : pool.getActiveConnections());
        hikari.put("idle", pool == null ? null : pool.getIdleConnections());
        hikari.put("pending", pool == null ? null : pool.getThreadsAwaitingConnection());
        hikari.put("total", pool == null ? null : pool.getTotalConnections());
        out.put("hikari", hikari);

        int pending = jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Integer.class);
        out.put("outbox", Map.of("pending", pending));
        out.put("readModel", readModelCounts());
        out.put("dashboards", dashboards(outcomes));
        return out;
    }

    Map<String, Object> readModelCounts() {
        int writeSide = jdbc.queryForObject("select count(*) from settlements", Integer.class);
        int readSide = jdbc.queryForObject("select count(*) from settlement_read_model", Integer.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("writeSide", writeSide);
        m.put("readSide", readSide);
        m.put("lag", writeSide - readSide);
        return m;
    }

    private Map<String, Object> http() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Timer t : registry.find("http.server.requests").tag("uri", "/settlements").tag("method", "POST").timers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", t.count());
            m.put("meanMs", t.mean(TimeUnit.MILLISECONDS));
            m.put("maxMs", t.max(TimeUnit.MILLISECONDS));
            for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
                m.put("p" + (int) Math.round(v.percentile() * 100) + "Ms", v.value(TimeUnit.MILLISECONDS));
            }
            out.put("POST /settlements status " + t.getId().getTag("status"), m);
        }
        return out;
    }

    private Map<String, Object> dashboards(Map<String, Double> outcomes) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("settlementOutcomes", Map.of("values", outcomes, "query", PROMQL_OUTCOMES, "source", "Prometheus (PromQL)"));
        d.put("backendP95", Map.of("query", PROMQL_P95, "source", "Prometheus (PromQL)",
                "note", "Needs histogram buckets: the demo profile enables management.metrics.distribution.percentiles-histogram."
                        + "http.server.requests. No config in the repo enables it for staging."));
        List<Double> scores = jdbc.queryForList(SQL_FRAUD_SCORES, Double.class);
        d.put("fraudScores", Map.of("scores", scores, "query", SQL_FRAUD_SCORES, "source", "Postgres (SQL)"));
        Map<String, Long> decisions = new LinkedHashMap<>();
        jdbc.query(SQL_FRAUD_DECISIONS, rs -> {
            decisions.put(rs.getString("decision"), rs.getLong("value"));
        });
        d.put("fraudDecisions", Map.of("values", decisions, "query", SQL_FRAUD_DECISIONS, "source", "Postgres (SQL)"));
        return d;
    }

    private Double gauge(String name) {
        var g = registry.find(name).gauge();
        return g == null ? null : g.value();
    }

    private HikariPoolMXBean pool() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (Exception e) {
            return null;
        }
    }
}
