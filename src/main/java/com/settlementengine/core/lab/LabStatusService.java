package com.settlementengine.core.lab;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the UI reads so it never hard-codes a cap, a timing, or a reset time. */
@LabComponent
public class LabStatusService {

    private final LabProperties props;
    private final Environment env;
    private final LabResetService reset;
    private final JdbcTemplate jdbc;

    public LabStatusService(LabProperties props, Environment env, LabResetService reset, JdbcTemplate jdbc) {
        this.props = props;
        this.env = env;
        this.reset = reset;
        this.jdbc = jdbc;
    }

    public Map<String, Object> snapshot() {
        long reconcileMs = env.getProperty("settlement-engine.reconciliation.scheduled-fixed-delay-ms", Long.class, 60000L);
        Instant now = Instant.now();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", now);
        out.put("readOnly", props.readOnly());

        Map<String, Object> time = new LinkedHashMap<>();
        time.put("reconciliationIntervalMs", reconcileMs);
        time.put("sweepIntervalMs", reconcileMs); // both schedulers read the same property
        time.put("reconciliationGraceSeconds",
                env.getProperty("settlement-engine.reconciliation.grace-period-seconds", Long.class, 300L));
        time.put("stalePendingGraceSeconds",
                env.getProperty("settlement-engine.reconciliation.stale-pending-grace-period-seconds", Long.class, 300L));
        time.put("outboxPollMs", env.getProperty("settlement-engine.outbox.poll-fixed-delay-ms", Long.class, 500L));
        time.put("invoiceRepaymentIntervalMs",
                env.getProperty("settlement-engine.invoice-financing.scheduled-fixed-delay-ms", Long.class, 60000L));
        out.put("timeCompression", time);

        Instant lastRunEnd = lastReconciliationFinish();
        Map<String, Object> next = new LinkedHashMap<>();
        // fixedDelay: the next run starts reconcileMs after the previous one finished. An estimate, not a promise.
        Instant nextReconciliation = lastRunEnd == null ? null : nextAfter(lastRunEnd, reconcileMs, now);
        next.put("reconciliationEstimate", nextReconciliation);
        next.put("sweepEstimate", nextReconciliation);
        next.put("note", "Estimate: both jobs run on a fixed delay of " + reconcileMs + " ms after the previous run finished.");
        out.put("next", next);

        LabResetState state = reset.state();
        Map<String, Object> resetInfo = new LinkedHashMap<>();
        resetInfo.put("lastResetAt", state == null ? null : state.lastResetAt());
        resetInfo.put("nextResetAt", state == null ? null : state.nextResetAt());
        resetInfo.put("autoResetMinutes", props.autoResetMinutes());
        resetInfo.put("cooldownSeconds", props.resetCooldownSeconds());
        out.put("reset", resetInfo);

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("maxVirtualUsers", props.load().maxVirtualUsers());
        caps.put("maxDurationSeconds", props.load().maxDurationSeconds());
        caps.put("maxTotalRequests", props.load().maxTotalRequests());
        caps.put("runCooldownSeconds", props.load().cooldownSeconds());
        caps.put("minAmount", props.load().minAmount());
        caps.put("maxAmount", props.load().maxAmount());
        caps.put("maxSlowMs", LabFaultPlan.MAX_SLOW_MS);
        caps.put("requestsPerMinutePerIp", props.requestsPerMinutePerIp());
        caps.put("sseMaxPerIp", props.sse().maxPerIp());
        caps.put("sseIdleTimeoutMinutes", props.sse().idleTimeoutMinutes());
        out.put("caps", caps);

        out.put("faultBoundaries", List.of(
                "the gateway call (request lost, response lost, declined, slow)",
                "the external record store (forget, corrupt)",
                "the database row a human would edit (account balance)",
                "a crash point between the two transactions (orphaned pending settlement)"));
        out.put("faultStatement", "Faults are injected only at the boundaries above. Nothing is changed inside the "
                + "engine's own logic: it sees exactly what it would see from a flaky external system or a careless human.");
        return out;
    }

    static Instant nextAfter(Instant lastFinish, long intervalMs, Instant now) {
        Instant candidate = lastFinish.plus(Duration.ofMillis(intervalMs));
        while (candidate.isBefore(now)) {
            candidate = candidate.plus(Duration.ofMillis(intervalMs));
        }
        return candidate;
    }

    private Instant lastReconciliationFinish() {
        List<java.sql.Timestamp> rows = jdbc.queryForList(
                "select finished_at from reconciliation_runs where finished_at is not null order by finished_at desc limit 1",
                java.sql.Timestamp.class);
        return rows.isEmpty() ? null : rows.get(0).toInstant();
    }
}
