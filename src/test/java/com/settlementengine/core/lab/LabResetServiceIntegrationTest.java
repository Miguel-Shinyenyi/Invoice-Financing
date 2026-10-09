package com.settlementengine.core.lab;

import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.invoicing.MockInvoicePaymentSource;
import com.settlementengine.core.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabResetServiceIntegrationTest extends AbstractLabIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LabResetService reset;
    @Autowired
    MockExternalSystem external;
    @Autowired
    MockInvoicePaymentSource payments;
    @Autowired
    LabOrphanedExternalRecords orphans;
    @Autowired
    ReconciliationService reconciliation;

    private static final String[] TABLES = {"ledger_accounts", "ledger_entries", "settlements", "idempotency_keys",
            "outbox_events", "settlement_read_model", "invoices", "advances", "fraud_assessments", "reconciliation_runs",
            "reconciliation_mismatches", "ledger_mismatches", "audit_log", "users"};

    private Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            out.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class));
        }
        return out;
    }

    private Map<String, BigDecimal> balances() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("select id::text, balance from ledger_accounts order by id",
                rs -> { out.put(rs.getString(1), rs.getBigDecimal(2)); });
        return out;
    }

    @Test
    void resetAfterAChaoticRunReturnsRowCountsAndBalancesToTheSeedExactly() {
        reset.reset();
        Map<String, Integer> seedCounts = counts();
        Map<String, BigDecimal> seedBalances = balances();

        // make a mess: extra rows, changed balances, polluted in-memory stores
        jdbc.update("update ledger_accounts set balance = balance + 999 where id = '10000000-0000-0000-0000-000000000001'");
        jdbc.update("delete from audit_log");
        jdbc.update("insert into reconciliation_runs (id, started_at, records_checked, mismatches_found, status) "
                + "values (gen_random_uuid(), now(), 0, 0, 'COMPLETED')");
        jdbc.update("delete from users where role = 'SUPPORT'");
        external.replaceAll(java.util.List.of());
        payments.markPaid("junk", BigDecimal.ONE);
        orphans.add(java.util.UUID.randomUUID(), "MOCK-JUNK");

        reset.reset();

        // reconciliation_runs is written by the real 5-second scheduled run, which can land between two snapshots;
        // every other table must match the seed exactly
        Map<String, Integer> after = counts();
        assertThat(after.get("reconciliation_runs")).isBetween(seedCounts.get("reconciliation_runs"), seedCounts.get("reconciliation_runs") + 2);
        after.remove("reconciliation_runs");
        seedCounts.remove("reconciliation_runs");
        assertThat(after).isEqualTo(seedCounts);
        assertThat(balances()).isEqualTo(seedBalances);
        assertThat(payments.paymentCount()).isZero();
        assertThat(orphans.all()).isEmpty();
    }

    @Test
    void resetRebuildsTheExternalStoreSoTheSeedReconcilesWithOnlyItsOneDeliberateMismatch() {
        reset.reset();
        assertThat(external.findByReference("MOCK-SEED-1")).isPresent();
        assertThat(external.findByReference("MOCK-SEED-11").orElseThrow().amount()).isEqualByComparingTo("99.00");

        reconciliation.runOnce();

        assertThat(jdbc.queryForObject(
                "select count(*) from reconciliation_mismatches where resolution_status = 'OPEN'", Integer.class))
                .as("only the seeded open mismatch for settlement 11").isEqualTo(1);
    }

    @Test
    void resetRecordsWhenItHappened() {
        reset.reset();
        assertThat(reset.state().lastResetAt()).isNotNull();
        assertThat(reset.state().nextResetAt()).isAfter(reset.state().lastResetAt());
    }
}
