package com.settlementengine.core.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs V7's backfill against data shaped like a real pre-V7 database (seeded accounts, some with
 * settlements applied, some zero-balance, one already inconsistent), rather than assuming it's
 * safe. Not a Spring test: it needs to stop Flyway at V6, insert rows, then migrate the rest.
 */
@Testcontainers
class LedgerOpeningEntriesMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final UUID UNTOUCHED = UUID.randomUUID();
    private static final UUID SETTLED_SOURCE = UUID.randomUUID();
    private static final UUID SETTLED_DESTINATION = UUID.randomUUID();
    private static final UUID ZERO_BALANCE = UUID.randomUUID();
    private static final UUID ALREADY_INCONSISTENT = UUID.randomUUID();

    @BeforeAll
    static void migrateToV6SeedThenMigrateRest() throws SQLException {
        flyway("6").migrate();

        try (Connection c = connection()) {
            insertAccount(c, UNTOUCHED, "500.00");
            // Started at 500 / 0, then one confirmed 100.00 settlement between them.
            insertAccount(c, SETTLED_SOURCE, "400.00");
            insertAccount(c, SETTLED_DESTINATION, "100.00");
            insertAccount(c, ZERO_BALANCE, "0.00");
            // Balance 50 but 100 was credited to it: net of entries exceeds the balance, which
            // no real starting balance could explain.
            insertAccount(c, ALREADY_INCONSISTENT, "50.00");

            UUID settlement = insertConfirmedSettlement(c, SETTLED_SOURCE, SETTLED_DESTINATION, "100.00");
            insertEntry(c, settlement, SETTLED_SOURCE, "DEBIT", "100.00");
            insertEntry(c, settlement, SETTLED_DESTINATION, "CREDIT", "100.00");

            UUID drift = insertConfirmedSettlement(c, SETTLED_SOURCE, ALREADY_INCONSISTENT, "100.00");
            insertEntry(c, drift, ALREADY_INCONSISTENT, "CREDIT", "100.00");
        }

        flyway(null).migrate();
    }

    @Test
    void untouchedAccountGetsOpeningEqualToItsBalance() throws SQLException {
        assertThat(openingAmount(UNTOUCHED)).isEqualByComparingTo("500.00");
        assertThat(net(UNTOUCHED)).isEqualByComparingTo("500.00");
    }

    @Test
    void settledAccountsGetOpeningEqualToTheirOriginalStartingBalance() throws SQLException {
        assertThat(openingAmount(SETTLED_SOURCE)).isEqualByComparingTo("500.00");
        assertThat(net(SETTLED_SOURCE)).isEqualByComparingTo("400.00");
        // Started at zero: CREDIT alone already explains its balance.
        assertThat(openingAmount(SETTLED_DESTINATION)).isNull();
        assertThat(net(SETTLED_DESTINATION)).isEqualByComparingTo("100.00");
    }

    @Test
    void zeroBalanceAccountGetsNoOpeningEntry() throws SQLException {
        assertThat(openingAmount(ZERO_BALANCE)).isNull();
        assertThat(net(ZERO_BALANCE)).isEqualByComparingTo("0");
    }

    @Test
    void alreadyInconsistentAccountIsLeftInconsistentNotCoerced() throws SQLException {
        assertThat(openingAmount(ALREADY_INCONSISTENT)).isNull();
        assertThat(net(ALREADY_INCONSISTENT)).isEqualByComparingTo("100.00");
    }

    @Test
    void settlementIdIsNullForOpeningAndRequiredOtherwise() throws SQLException {
        try (Connection c = connection()) {
            UUID settlement = insertConfirmedSettlement(c, UNTOUCHED, ZERO_BALANCE, "1.00");
            assertThatThrownBy(() -> insertEntry(c, settlement, UNTOUCHED, "OPENING", "1.00"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ledger_entries_settlement_id_by_type_check");
            assertThatThrownBy(() -> insertEntry(c, null, UNTOUCHED, "CREDIT", "1.00"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ledger_entries_settlement_id_by_type_check");
        }
    }

    @Test
    void ledgerMismatchesTableExistsAfterV8() throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from ledger_mismatches")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insertAccount(Connection c, UUID id, String balance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into ledger_accounts (id, owner_id, balance, currency) values (?, ?, ?, 'USD')")) {
            ps.setObject(1, id);
            ps.setObject(2, UUID.randomUUID());
            ps.setBigDecimal(3, new BigDecimal(balance));
            ps.executeUpdate();
        }
    }

    private static UUID insertConfirmedSettlement(Connection c, UUID source, UUID destination, String amount)
            throws SQLException {
        UUID key = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into idempotency_keys (key, request_hash, status) values (?, 'hash', 'COMPLETED')")) {
            ps.setObject(1, key);
            ps.executeUpdate();
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into settlements (id, idempotency_key, source_account_id, destination_account_id, amount, currency, status) "
                        + "values (?, ?, ?, ?, ?, 'USD', 'CONFIRMED')")) {
            ps.setObject(1, id);
            ps.setObject(2, key);
            ps.setObject(3, source);
            ps.setObject(4, destination);
            ps.setBigDecimal(5, new BigDecimal(amount));
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertEntry(Connection c, UUID settlementId, UUID accountId, String type, String amount)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into ledger_entries (id, settlement_id, account_id, entry_type, amount) values (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, settlementId);
            ps.setObject(3, accountId);
            ps.setString(4, type);
            ps.setBigDecimal(5, new BigDecimal(amount));
            ps.executeUpdate();
        }
    }

    private static BigDecimal openingAmount(UUID accountId) throws SQLException {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "select amount from ledger_entries where account_id = ? and entry_type = 'OPENING'")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                BigDecimal amount = rs.getBigDecimal(1);
                assertThat(rs.next()).as("at most one OPENING entry per account").isFalse();
                return amount;
            }
        }
    }

    private static BigDecimal net(UUID accountId) throws SQLException {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "select coalesce(sum(case when entry_type = 'DEBIT' then -amount else amount end), 0) "
                        + "from ledger_entries where account_id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }
}
