package com.settlementengine.core.lab;

import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerInconsistencyException;
import com.settlementengine.core.reconciliation.LedgerConsistencyService;
import com.settlementengine.core.repository.LedgerAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** End-of-run checks against the database. Reuses {@link LedgerConsistencyService}; does not reimplement it. */
@LabComponent
public class LabLoadInvariants {

    /** Balances captured before the run starts. */
    public record Baseline(BigDecimal poolTotal, BigDecimal dupSourceBalance, BigDecimal dupDestinationBalance) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerAccountRepository accounts;
    private final LedgerConsistencyService ledgerConsistency;
    private final long staleGraceSeconds;

    public LabLoadInvariants(JdbcTemplate jdbc, LedgerAccountRepository accounts, LedgerConsistencyService ledgerConsistency,
                             @Value("${settlement-engine.reconciliation.stale-pending-grace-period-seconds:300}") long staleGraceSeconds) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledgerConsistency = ledgerConsistency;
        this.staleGraceSeconds = staleGraceSeconds;
    }

    public Baseline baseline() {
        return new Baseline(poolTotal(), balance(LabLoadScope.DUP_SOURCE), balance(LabLoadScope.DUP_DESTINATION));
    }

    public List<LabInvariant> check(LoadPlan plan, Baseline before, UUID burstKey) {
        List<LabInvariant> out = new ArrayList<>();
        out.add(poolTotalUnchanged(before));
        out.add(duplicatePair(plan, before, burstKey));
        out.add(balancesMatchEntries());
        out.add(noStalePending());
        out.add(outboxDrained());
        out.add(readModelMatches());
        return out;
    }

    private LabInvariant poolTotalUnchanged(Baseline before) {
        String id = "POOL_TOTAL_UNCHANGED";
        String title = "The pool's total balance is unchanged";
        BigDecimal after = poolTotal();
        return after.compareTo(before.poolTotal()) == 0
                ? LabInvariant.pass(id, title, "total " + after + " before and after: money moved within the pool, none created or destroyed")
                : LabInvariant.fail(id, title, "before " + before.poolTotal() + ", after " + after);
    }

    private LabInvariant duplicatePair(LoadPlan plan, Baseline before, UUID burstKey) {
        String id = "DUPLICATE_KEY_MOVED_ONCE";
        String title = "The duplicate-key pair moved by exactly one settlement's amount";
        if (plan.scenario() != LoadScenario.DUPLICATE_KEY_BURST || burstKey == null) {
            return LabInvariant.skipped(id, title, "not exercised by the " + plan.scenario() + " scenario");
        }
        List<String> statuses = jdbc.queryForList("select status from settlements where idempotency_key = ?", String.class, burstKey);
        BigDecimal expected = statuses.size() == 1 && statuses.get(0).equals("CONFIRMED") ? plan.amount() : BigDecimal.ZERO;
        BigDecimal sourceMoved = before.dupSourceBalance().subtract(balance(LabLoadScope.DUP_SOURCE));
        BigDecimal destMoved = balance(LabLoadScope.DUP_DESTINATION).subtract(before.dupDestinationBalance());
        boolean ok = statuses.size() <= 1 && sourceMoved.compareTo(expected) == 0 && destMoved.compareTo(expected) == 0;
        String detail = statuses.size() + " settlement row(s) for the shared key (" + statuses + "), source moved " + sourceMoved
                + ", destination moved " + destMoved + ", expected " + expected;
        return ok ? LabInvariant.pass(id, title, detail) : LabInvariant.fail(id, title, detail);
    }

    private LabInvariant balancesMatchEntries() {
        String id = "BALANCES_MATCH_ENTRIES";
        String title = "Every touched account's balance equals the sum of its ledger entries";
        List<UUID> scope = new ArrayList<>(LabLoadScope.POOL);
        scope.addAll(List.of(LabLoadScope.DUP_SOURCE, LabLoadScope.DUP_DESTINATION, LabLoadScope.BUSINESS, LabLoadScope.PLATFORM));
        List<String> bad = new ArrayList<>();
        for (UUID accountId : scope) {
            LedgerAccount account = accounts.findById(accountId).orElse(null);
            if (account == null) {
                bad.add(accountId + " missing");
                continue;
            }
            try {
                ledgerConsistency.verify(account);
            } catch (LedgerInconsistencyException e) {
                bad.add(e.getMessage());
            }
        }
        return bad.isEmpty() ? LabInvariant.pass(id, title, scope.size() + " accounts checked with LedgerConsistencyService")
                : LabInvariant.fail(id, title, String.join("; ", bad));
    }

    private LabInvariant noStalePending() {
        String id = "NO_STALE_PENDING";
        String title = "No settlement is still PENDING beyond the grace period";
        String sql = "select count(*) from settlements where status = 'PENDING' and updated_at < now() - make_interval(secs => ?)";
        boolean ok = await(() -> jdbc.queryForObject(sql, Integer.class, (double) staleGraceSeconds) == 0, Duration.ofSeconds(15));
        int remaining = jdbc.queryForObject(sql, Integer.class, (double) staleGraceSeconds);
        return ok ? LabInvariant.pass(id, title, "0 stale PENDING (grace " + staleGraceSeconds + "s)")
                : LabInvariant.fail(id, title, remaining + " settlement(s) PENDING beyond " + staleGraceSeconds + "s");
    }

    private LabInvariant outboxDrained() {
        String id = "OUTBOX_DRAINED";
        String title = "The outbox drains to zero pending";
        String sql = "select count(*) from outbox_events where published_at is null";
        boolean ok = await(() -> jdbc.queryForObject(sql, Integer.class) == 0, Duration.ofSeconds(20));
        int pending = jdbc.queryForObject(sql, Integer.class);
        return ok ? LabInvariant.pass(id, title, "0 unpublished outbox rows")
                : LabInvariant.fail(id, title, pending + " outbox row(s) still unpublished after 20s");
    }

    private LabInvariant readModelMatches() {
        String id = "READ_MODEL_MATCHES";
        String title = "The read model has a row for every settlement";
        String sql = "select (select count(*) from settlements) - (select count(*) from settlement_read_model)";
        boolean ok = await(() -> jdbc.queryForObject(sql, Integer.class) == 0, Duration.ofSeconds(20));
        int lag = jdbc.queryForObject(sql, Integer.class);
        return ok ? LabInvariant.pass(id, title, "settlements and read-model rows are equal in number")
                : LabInvariant.fail(id, title, "read model lags the write side by " + lag + " row(s) after 20s");
    }

    private BigDecimal poolTotal() {
        String placeholders = String.join(",", java.util.Collections.nCopies(LabLoadScope.POOL.size(), "?"));
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from ledger_accounts where id in (" + placeholders + ")",
                BigDecimal.class, LabLoadScope.POOL.toArray());
    }

    private BigDecimal balance(UUID id) {
        return jdbc.queryForObject("select balance from ledger_accounts where id = ?", BigDecimal.class, id);
    }

    static boolean await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
