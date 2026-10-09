package com.settlementengine.core.lab;

import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.reconciliation.ExternalRecord;
import com.settlementengine.core.reconciliation.ExternalStatus;
import com.settlementengine.core.reconciliation.ReconciliationRun;
import com.settlementengine.core.reconciliation.ReconciliationService;
import com.settlementengine.core.reconciliation.StalePendingSettlementSweepService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Faults at the boundaries that are NOT the gateway call: the external record store, and the database
 * row a human would edit. Plus manual runs of the engine's own jobs.
 */
@LabComponent
public class LabChaosService {

    static final BigDecimal MAX_HAND_EDIT = new BigDecimal("10000.00");

    private final MockExternalSystem external;
    private final JdbcTemplate jdbc;
    private final ReconciliationService reconciliationService;
    private final StalePendingSettlementSweepService sweepService;

    public LabChaosService(MockExternalSystem external, JdbcTemplate jdbc, ReconciliationService reconciliationService,
                           StalePendingSettlementSweepService sweepService) {
        this.external = external;
        this.jdbc = jdbc;
        this.reconciliationService = reconciliationService;
        this.sweepService = sweepService;
    }

    public record CorruptRequest(BigDecimal amount, String currency, String status) {
    }

    public Map<String, Object> forget(String ref) {
        requireRecord(ref);
        external.forget(ref);
        return Map.of("externalRef", ref, "action", "forgotten",
                "expected", "After the grace period, reconciliation flags 'No external record found'.");
    }

    public Map<String, Object> corrupt(String ref, CorruptRequest req) {
        ExternalRecord current = requireRecord(ref);
        if (req == null || (req.amount() == null && req.currency() == null && req.status() == null)) {
            throw new LabValidationException("give at least one of amount, currency, status");
        }
        BigDecimal amount = current.amount();
        if (req.amount() != null) {
            amount = req.amount();
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                    || amount.compareTo(new BigDecimal("1000000")) > 0) {
                throw new LabValidationException("amount must be positive, at most 2 decimals, at most 1000000");
            }
        }
        String currency = current.currency();
        if (req.currency() != null) {
            currency = req.currency();
            if (!currency.matches("[A-Z]{3}")) {
                throw new LabValidationException("currency must be a 3-letter code");
            }
        }
        ExternalStatus status = current.status();
        if (req.status() != null) {
            try {
                status = ExternalStatus.valueOf(req.status().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new LabValidationException("status must be CONFIRMED or FAILED");
            }
        }
        external.corrupt(ref, new ExternalRecord(ref, amount, currency, status));
        return Map.of("externalRef", ref, "action", "corrupted", "amount", amount, "currency", currency,
                "status", status.name());
    }

    /** A direct row update, mimicking a human editing the database. */
    public Map<String, Object> handEditBalance(UUID accountId, BigDecimal delta) {
        if (delta == null || delta.signum() == 0 || delta.abs().compareTo(MAX_HAND_EDIT) > 0 || delta.scale() > 2) {
            throw new LabValidationException("delta must be non-zero, at most 2 decimals, within +/-" + MAX_HAND_EDIT);
        }
        int updated = jdbc.update("update ledger_accounts set balance = balance + ? where id = ?", delta, accountId);
        if (updated == 0) {
            throw new LabNotFoundException("No account " + accountId);
        }
        return accountState(accountId, "hand-edited by " + delta);
    }

    /** What a human editing the database would do to close the loop. Lab-only helper. */
    public Map<String, Object> repairBalance(UUID accountId) {
        int updated = jdbc.update("""
                update ledger_accounts a set balance = coalesce((
                    select sum(case when e.entry_type = 'DEBIT' then -e.amount else e.amount end)
                    from ledger_entries e where e.account_id = a.id), 0)
                where a.id = ?""", accountId);
        if (updated == 0) {
            throw new LabNotFoundException("No account " + accountId);
        }
        return accountState(accountId, "balance set back to the sum of its entries");
    }

    public ReconciliationRun runReconciliation() {
        return reconciliationService.runOnce();
    }

    public void runSweep() {
        sweepService.runOnce();
    }

    private Map<String, Object> accountState(UUID id, String action) {
        Map<String, Object> row = jdbc.queryForMap("""
                select a.balance as stored,
                       coalesce(sum(case when e.entry_type = 'DEBIT' then -e.amount else e.amount end), 0) as computed
                from ledger_accounts a left join ledger_entries e on e.account_id = a.id
                where a.id = ? group by a.id, a.balance""", id);
        return Map.of("accountId", id, "action", action, "storedBalance", row.get("stored"),
                "computedBalance", row.get("computed"));
    }

    private ExternalRecord requireRecord(String ref) {
        return external.findByReference(ref)
                .orElseThrow(() -> new LabNotFoundException("The external system holds no record " + ref));
    }
}
