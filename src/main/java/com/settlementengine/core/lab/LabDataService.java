package com.settlementengine.core.lab;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only table browser and audit viewer. Table and column names are never taken from the visitor: they are
 * looked up in the fixed whitelist below and only the whitelist's own strings reach the SQL.
 */
@LabComponent
public class LabDataService {

    static final int PAGE_SIZE = 25;
    static final int MAX_PAGE = 10_000;

    private record Table(List<String> columns, String orderBy) {
    }

    /** Never users, refresh_tokens, audit_log (has its own viewer), or any hash column (request_hash, password_hash, token_hash). */
    static final Map<String, Table> WHITELIST = Map.ofEntries(
            Map.entry("settlements", new Table(List.of("id", "idempotency_key", "source_account_id", "destination_account_id",
                    "amount", "currency", "status", "external_ref", "created_at", "updated_at"), "created_at desc, id")),
            Map.entry("idempotency_keys", new Table(List.of("key", "response_snapshot", "status", "created_at", "updated_at"),
                    "created_at desc, key")),
            Map.entry("ledger_accounts", new Table(List.of("id", "owner_id", "balance", "currency", "version", "created_at"),
                    "created_at desc, id")),
            Map.entry("ledger_entries", new Table(List.of("id", "settlement_id", "account_id", "entry_type", "amount",
                    "created_at"), "created_at desc, id")),
            Map.entry("outbox_events", new Table(List.of("id", "aggregate_type", "aggregate_id", "topic", "payload",
                    "created_at", "published_at"), "created_at desc, id")),
            Map.entry("settlement_read_model", new Table(List.of("settlement_id", "source_account_id", "destination_account_id",
                    "amount", "currency", "status", "updated_at"), "updated_at desc, settlement_id")),
            Map.entry("invoices", new Table(List.of("id", "business_account_id", "customer_reference", "amount", "currency",
                    "due_date", "status", "external_source_ref", "created_at", "updated_at"), "created_at desc, id")),
            Map.entry("advances", new Table(List.of("id", "invoice_id", "amount_advanced", "fee", "disbursed_settlement_id",
                    "repaid_settlement_id", "status", "created_at"), "created_at desc, id")),
            Map.entry("fraud_assessments", new Table(List.of("id", "invoice_id", "score", "decision", "reasons", "created_at"),
                    "created_at desc, id")),
            Map.entry("reconciliation_runs", new Table(List.of("id", "started_at", "finished_at", "records_checked",
                    "mismatches_found", "status"), "started_at desc, id")),
            Map.entry("reconciliation_mismatches", new Table(List.of("id", "run_id", "settlement_id", "internal_state",
                    "external_state", "details", "resolution_status", "resolved_at", "created_at"), "created_at desc, id")),
            Map.entry("ledger_mismatches", new Table(List.of("id", "account_id", "stored_balance", "computed_balance", "details",
                    "resolution_status", "resolved_at", "created_at"), "created_at desc, id")));

    private final JdbcTemplate jdbc;

    public LabDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static List<String> tables() {
        return WHITELIST.keySet().stream().sorted().toList();
    }

    public Map<String, Object> browse(String tableName, String page, String columnsParam) {
        Table table = WHITELIST.get(tableName);
        if (table == null) {
            throw new LabNotFoundException("No browsable table '" + tableName + "'");
        }
        int pageNumber = parsePage(page);
        List<String> columns = table.columns();
        if (columnsParam != null && !columnsParam.isBlank()) {
            columns = new ArrayList<>();
            for (String requested : columnsParam.split(",", -1)) {
                String c = requested.trim();
                if (!table.columns().contains(c)) {
                    throw new LabValidationException("Column '" + c + "' is not available on " + tableName);
                }
                columns.add(c);
            }
        }
        // identifiers below come only from the whitelist
        String sql = "select " + String.join(", ", columns) + " from " + tableName + " order by " + table.orderBy()
                + " limit " + PAGE_SIZE + " offset ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, pageNumber * PAGE_SIZE);
        long total = jdbc.queryForObject("select count(*) from " + tableName, Long.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", tableName);
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("page", pageNumber);
        out.put("pageSize", PAGE_SIZE);
        out.put("total", total);
        out.put("tables", tables());
        return out;
    }

    public Map<String, Object> audit(String actor, String action, String since, int limit) {
        int capped = Math.max(1, Math.min(200, limit));
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (java.time.format.DateTimeParseException e) {
                throw new LabValidationException("since must be an ISO-8601 instant");
            }
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select a.id, a.action, a.target_table, a.target_id, a.outcome, a.created_at, a.actor_id,
                       coalesce(u.username, case when a.actor_id is null then 'system' else 'unknown user' end) as actor
                from audit_log a left join users u on u.id = a.actor_id where 1 = 1""");
        if (actor != null && !actor.isBlank()) {
            sql.append(" and u.username = ?");
            args.add(actor);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" and a.action = ?");
            args.add(action);
        }
        if (sinceInstant != null) {
            sql.append(" and a.created_at >= ?");
            args.add(java.sql.Timestamp.from(sinceInstant));
        }
        sql.append(" order by a.created_at desc limit ?");
        args.add(capped);
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getObject("id"));
            r.put("action", rs.getString("action"));
            r.put("entity", rs.getString("target_table"));
            r.put("entityId", rs.getObject("target_id"));
            r.put("outcome", rs.getString("outcome"));
            r.put("actor", rs.getString("actor"));
            r.put("at", rs.getTimestamp("created_at").toInstant());
            return r;
        }, args.toArray());
        List<String> actions = jdbc.queryForList("select distinct action from audit_log order by action", String.class);
        return Map.of("rows", rows, "actions", actions);
    }

    private static int parsePage(String page) {
        if (page == null || page.isBlank()) {
            return 0;
        }
        try {
            int p = Integer.parseInt(page);
            if (p < 0 || p > MAX_PAGE) {
                throw new LabValidationException("page must be between 0 and " + MAX_PAGE);
            }
            return p;
        } catch (NumberFormatException e) {
            throw new LabValidationException("page must be a number");
        }
    }
}
