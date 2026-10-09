package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A seed that fails these checks is a bug: every demo page reads from it. */
class LabSeedIntegrityTest extends AbstractLabIntegrationTest {

    static final String DRIFTED_ACCOUNT = "50000000-0000-0000-0000-000000000002";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LabResetService reset;

    @Test
    void everyStoredBalanceEqualsTheSumOfItsEntriesExceptTheDeliberatelyDriftedAccount() {
        reset.reset();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.id::text as id, a.balance,
                       coalesce(sum(case when e.entry_type = 'DEBIT' then -e.amount else e.amount end), 0) as net
                from ledger_accounts a left join ledger_entries e on e.account_id = a.id
                group by a.id, a.balance""");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            java.math.BigDecimal balance = (java.math.BigDecimal) row.get("balance");
            java.math.BigDecimal net = (java.math.BigDecimal) row.get("net");
            if (DRIFTED_ACCOUNT.equals(row.get("id"))) {
                assertThat(balance.subtract(net)).as("drifted account").isEqualByComparingTo("25.00");
            } else {
                assertThat(balance).as("account " + row.get("id")).isEqualByComparingTo(net);
            }
        }
    }

    @Test
    void everyAccountSeededWithMoneyHasExactlyOneOpeningEntryAndNoneIsNegative() {
        reset.reset();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where entry_type = 'OPENING'", Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "select count(*) from (select account_id from ledger_entries where entry_type = 'OPENING' "
                        + "group by account_id having count(*) > 1) d", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_accounts where balance < 0", Integer.class)).isZero();
    }

    @Test
    void settlementsExistInEveryStatusAndTheReversedOneHasNoExternalRef() {
        reset.reset();
        List<String> statuses = jdbc.queryForList("select distinct status from settlements", String.class);
        assertThat(statuses).containsExactlyInAnyOrder("PENDING", "CONFIRMED", "FAILED", "UNKNOWN", "REVERSED");
        assertThat(jdbc.queryForObject(
                "select count(*) from settlements where status = 'REVERSED' and external_ref is null", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void invoicesExistInEveryStatus() {
        reset.reset();
        assertThat(jdbc.queryForList("select distinct status from invoices", String.class))
                .containsExactlyInAnyOrder("ISSUED", "FINANCED", "REPAID", "OVERDUE");
    }

    @Test
    void theHistoryIsLivedIn() {
        reset.reset();
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_runs", Integer.class)).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject(
                "select count(*) from reconciliation_mismatches where resolution_status = 'RESOLVED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from reconciliation_mismatches where resolution_status = 'OPEN'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_mismatches where resolution_status = 'OPEN'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_mismatches where resolution_status = 'RESOLVED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isGreaterThan(0);
    }

    @Test
    void theReadOnlyPersonaOwnsSpecificAccounts() {
        reset.reset();
        assertThat(jdbc.queryForObject("""
                select count(*) from ledger_accounts a join users u on u.owner_id = a.owner_id
                where u.role = 'READ_ONLY'""", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(3);
    }
}
