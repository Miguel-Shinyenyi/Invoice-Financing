package com.settlementengine.core.lab;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the real API does not list: resolved mismatches, run history and the stranded UNKNOWN settlements. Plus the
 * "unseen mismatches" count that makes Known gap 6 visible: nothing alerts a human, a mismatch is seen only if
 * someone opens the reconciliation screen.
 */
@LabComponent
public class LabReconciliationViewService implements LabResettable {

    static final String STRANDED_EXPLANATION = "UNKNOWN with no external reference. ReconciliationService.runOnce only loads "
            + "settlements that have one (findByExternalRefIsNotNull), so these are never picked up and no mismatch is ever "
            + "opened for them. Only an UNKNOWN that carries a ref gets auto-resolved. Known gap 1.";

    private final JdbcTemplate jdbc;
    private volatile Instant lastSeenAt = Instant.now();

    public LabReconciliationViewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markSeen() {
        lastSeenAt = Instant.now();
    }

    @Override
    public void resetInMemory() {
        lastSeenAt = Instant.now();
    }

    public List<Map<String, Object>> runs(int limit) {
        int capped = Math.max(1, Math.min(100, limit));
        return jdbc.queryForList("select id, started_at as \"startedAt\", finished_at as \"finishedAt\", "
                + "records_checked as \"recordsChecked\", mismatches_found as \"mismatchesFound\", status "
                + "from reconciliation_runs order by started_at desc limit ?", capped);
    }

    public List<Map<String, Object>> mismatches(String status) {
        return jdbc.queryForList("select id, run_id as \"runId\", settlement_id as \"settlementId\", "
                + "internal_state as \"internalState\", external_state as \"externalState\", details, "
                + "resolution_status as \"resolutionStatus\", resolved_at as \"resolvedAt\", created_at as \"createdAt\" "
                + "from reconciliation_mismatches where resolution_status = ? order by created_at desc", requireStatus(status));
    }

    public List<Map<String, Object>> ledgerMismatches(String status) {
        return jdbc.queryForList("select id, account_id as \"accountId\", stored_balance as \"storedBalance\", "
                + "computed_balance as \"computedBalance\", details, resolution_status as \"resolutionStatus\", "
                + "resolved_at as \"resolvedAt\", created_at as \"createdAt\" "
                + "from ledger_mismatches where resolution_status = ? order by created_at desc", requireStatus(status));
    }

    public Map<String, Object> stranded() {
        List<Map<String, Object>> rows = jdbc.queryForList("select id as \"settlementId\", source_account_id as \"sourceAccountId\", "
                + "destination_account_id as \"destinationAccountId\", amount, currency, status, external_ref as \"externalRef\", "
                + "created_at as \"createdAt\", updated_at as \"updatedAt\" from settlements "
                + "where status = 'UNKNOWN' and external_ref is null order by updated_at desc limit 200");
        return Map.of("settlements", rows, "explanation", STRANDED_EXPLANATION);
    }

    public Map<String, Object> summary() {
        Timestamp since = Timestamp.from(lastSeenAt);
        int openSettlement = count("select count(*) from reconciliation_mismatches where resolution_status = 'OPEN'");
        int openLedger = count("select count(*) from ledger_mismatches where resolution_status = 'OPEN'");
        int unseen = jdbc.queryForObject("select (select count(*) from reconciliation_mismatches where resolution_status = 'OPEN' "
                + "and created_at > ?) + (select count(*) from ledger_mismatches where resolution_status = 'OPEN' and created_at > ?)",
                Integer.class, since, since);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openMismatches", openSettlement);
        out.put("openLedgerMismatches", openLedger);
        out.put("unseenMismatches", unseen);
        out.put("lastSeenAt", lastSeenAt);
        out.put("strandedUnknown", count("select count(*) from settlements where status = 'UNKNOWN' and external_ref is null"));
        out.put("note", "Unseen = open mismatches opened since someone last viewed this screen. Nothing alerts a human: "
                + "reconciliation.mismatch_found has no consumer and no Prometheus rule covers it. Known gap 6.");
        return out;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String requireStatus(String status) {
        if ("OPEN".equals(status) || "RESOLVED".equals(status)) {
            return status;
        }
        throw new LabValidationException("status must be OPEN or RESOLVED");
    }
}
