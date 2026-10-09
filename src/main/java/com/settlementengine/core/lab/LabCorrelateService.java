package com.settlementengine.core.lab;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One request id, every piece that proves the parts connect: both services' log lines, the trace, the audit rows, the
 * settlement inspector and the outbox events. Matching rules are stated per section (by id from the log lines, or by
 * actor and the request's time window), because audit_log and outbox_events do not store the request id themselves.
 */
@LabComponent
public class LabCorrelateService {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Duration SLACK = Duration.ofMillis(500);

    private final LabLogBuffer logs;
    private final LabRequestIndex index;
    private final LabJaegerService jaeger;
    private final LabInspector inspector;
    private final JdbcTemplate jdbc;

    public LabCorrelateService(LabLogBuffer logs, LabRequestIndex index, LabJaegerService jaeger, LabInspector inspector,
                               JdbcTemplate jdbc) {
        this.logs = logs;
        this.index = index;
        this.jaeger = jaeger;
        this.inspector = inspector;
        this.jdbc = jdbc;
    }

    public Map<String, Object> correlate(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new LabValidationException("requestId may contain letters, digits, '.', '_' and '-' only");
        }
        LabRequestIndex.Entry entry = index.find(requestId).orElse(null);
        List<LabLogEvent> lines = logs.query(new LabLogFilter(null, null, requestId, null, null, null, null), 500);

        Set<String> settlementIds = new LinkedHashSet<>();
        Set<String> invoiceIds = new LinkedHashSet<>();
        Set<String> traceIds = new LinkedHashSet<>();
        for (LabLogEvent e : lines) {
            if (e.settlementId() != null) {
                settlementIds.add(e.settlementId());
            }
            if (e.invoiceId() != null) {
                invoiceIds.add(e.invoiceId());
            }
            if (e.traceId() != null) {
                traceIds.add(e.traceId());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("request", entry == null ? null : requestView(entry));
        out.put("logs", lines);
        out.put("settlementIds", settlementIds);
        out.put("invoiceIds", invoiceIds);
        out.put("traces", traces(traceIds));
        out.put("audit", entry == null ? List.of() : audit(entry, settlementIds, invoiceIds));
        out.put("outbox", outbox(entry, settlementIds));
        out.put("inspector", settlementIds.isEmpty() ? null : inspect(settlementIds.iterator().next()));
        return out;
    }

    private Map<String, Object> requestView(LabRequestIndex.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", e.method());
        m.put("path", e.path());
        m.put("status", e.status());
        m.put("startedAt", e.startedAt());
        m.put("endedAt", e.endedAt());
        m.put("durationMs", Duration.between(e.startedAt(), e.endedAt()).toMillis());
        m.put("actor", e.actor());
        return m;
    }

    private Map<String, Object> traces(Set<String> traceIds) {
        Map<String, Object> t = new LinkedHashMap<>();
        List<LabJaegerService.TraceView> items = new ArrayList<>();
        String error = null;
        for (String id : traceIds) {
            if (items.size() >= 5) {
                break;
            }
            try {
                items.add(jaeger.trace(id));
            } catch (LabUpstreamUnavailableException e) {
                error = "Jaeger is not reachable";
            } catch (RuntimeException e) {
                error = error == null ? "trace " + id + " is not stored" : error;
            }
        }
        t.put("available", error == null || !items.isEmpty());
        t.put("traceIds", traceIds);
        t.put("items", items);
        t.put("error", items.isEmpty() ? error : null);
        return t;
    }

    private List<Map<String, Object>> audit(LabRequestIndex.Entry e, Set<String> settlementIds, Set<String> invoiceIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        if (e.userId() != null) {
            for (Map<String, Object> r : jdbc.queryForList("""
                    select id, action, target_table, target_id, outcome, created_at from audit_log
                    where actor_id = ? and created_at between ? and ? order by created_at""", e.userId(),
                    Timestamp.from(e.startedAt().minus(SLACK)), Timestamp.from(e.endedAt().plus(SLACK)))) {
                if (seen.add(r.get("id"))) {
                    r.put("matchedBy", "same actor, inside the request's time window");
                    rows.add(r);
                }
            }
        }
        List<UUID> ids = new ArrayList<>();
        settlementIds.forEach(s -> ids.add(UUID.fromString(s)));
        invoiceIds.forEach(s -> ids.add(UUID.fromString(s)));
        for (UUID id : ids) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "select id, action, target_table, target_id, outcome, created_at from audit_log where target_id = ?", id)) {
                if (seen.add(r.get("id"))) {
                    r.put("matchedBy", "target id found in this request's log lines");
                    rows.add(r);
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> outbox(LabRequestIndex.Entry e, Set<String> settlementIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        for (String id : settlementIds) {
            for (Map<String, Object> r : jdbc.queryForList("select id, topic, aggregate_id, created_at, published_at "
                    + "from outbox_events where aggregate_id = ? order by created_at", UUID.fromString(id))) {
                if (seen.add(r.get("id"))) {
                    r.put("matchedBy", "aggregate id found in this request's log lines");
                    rows.add(r);
                }
            }
        }
        if (e != null) {
            for (Map<String, Object> r : jdbc.queryForList("select id, topic, aggregate_id, created_at, published_at "
                            + "from outbox_events where created_at between ? and ? order by created_at",
                    Timestamp.from(e.startedAt().minus(SLACK)), Timestamp.from(e.endedAt().plus(SLACK)))) {
                if (seen.add(r.get("id"))) {
                    r.put("matchedBy", "written during the request's time window");
                    rows.add(r);
                }
            }
        }
        return rows;
    }

    private LabInspection inspect(String settlementId) {
        try {
            return inspector.inspect(UUID.fromString(settlementId));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
