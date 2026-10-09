package com.settlementengine.core.lab;

import com.settlementengine.core.gateway.MockExternalSystem;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only view of one settlement's footprint across the tables, the logs and the external store. */
@LabComponent
public class LabInspector {

    private final JdbcTemplate jdbc;
    private final LabLogBuffer logs;
    private final MockExternalSystem external;
    private final LabOrphanedExternalRecords orphans;

    public LabInspector(JdbcTemplate jdbc, LabLogBuffer logs, MockExternalSystem external,
                        LabOrphanedExternalRecords orphans) {
        this.jdbc = jdbc;
        this.logs = logs;
        this.external = external;
        this.orphans = orphans;
    }

    public LabInspection inspect(UUID settlementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from settlements where id = ?", settlementId);
        if (rows.isEmpty()) {
            throw new LabNotFoundException("No settlement " + settlementId);
        }
        Map<String, Object> settlement = rows.get(0);
        Object key = settlement.get("idempotency_key");
        Map<String, Object> idempotency = jdbc.queryForList("select * from idempotency_keys where key = ?", key)
                .stream().findFirst().orElse(null);
        List<Map<String, Object>> entries = jdbc.queryForList(
                "select * from ledger_entries where settlement_id = ? order by created_at, entry_type", settlementId);
        List<Map<String, Object>> outbox = jdbc.queryForList(
                "select id, aggregate_type, aggregate_id, topic, payload, created_at, published_at "
                        + "from outbox_events where aggregate_id = ? order by created_at", settlementId);
        List<Map<String, Object>> mismatches = jdbc.queryForList(
                "select * from reconciliation_mismatches where settlement_id = ? order by created_at", settlementId);
        List<Map<String, Object>> ledgerMismatches = jdbc.queryForList(
                "select * from ledger_mismatches where account_id in (?, ?) order by created_at",
                settlement.get("source_account_id"), settlement.get("destination_account_id"));
        List<Map<String, Object>> audit = jdbc.queryForList("""
                select * from audit_log where target_id = ?
                   or target_id in (select id from reconciliation_mismatches where settlement_id = ?)
                order by created_at""", settlementId, settlementId);
        List<LabLogEvent> events = logs.query(new LabLogFilter(null, null, null, settlementId.toString(), null, null, null), 200);
        List<String> traceIds = events.stream().map(LabLogEvent::traceId).filter(t -> t != null && !t.isBlank())
                .distinct().toList();

        String ref = (String) settlement.get("external_ref");
        String orphanRef = orphans.all().stream().filter(o -> o.settlementId().equals(settlementId))
                .map(LabOrphanedExternalRecords.Orphan::externalRef).findFirst().orElse(null);
        boolean held = (ref != null && external.findByReference(ref).isPresent())
                || (orphanRef != null && external.findByReference(orphanRef).isPresent());
        return new LabInspection(settlement, idempotency, entries, outbox, mismatches, ledgerMismatches, audit, events,
                traceIds, new LabInspection.ExternalView(ref, held, orphanRef));
    }
}
