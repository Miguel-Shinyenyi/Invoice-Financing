package com.settlementengine.core.reconciliation;

import com.settlementengine.core.domain.IllegalStateTransitionException;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementOutcome;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.SettlementTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class StalePendingSettlementSweepService {

    private static final Logger log = LoggerFactory.getLogger(StalePendingSettlementSweepService.class);

    private final SettlementRepository settlementRepository;
    private final SettlementTransactions settlementTransactions;
    private final Duration gracePeriod;

    public StalePendingSettlementSweepService(SettlementRepository settlementRepository,
                                               SettlementTransactions settlementTransactions,
                                               @Value("${settlement-engine.reconciliation.stale-pending-grace-period-seconds:300}") long gracePeriodSeconds) {
        this.settlementRepository = settlementRepository;
        this.settlementTransactions = settlementTransactions;
        this.gracePeriod = Duration.ofSeconds(gracePeriodSeconds);
    }

    /**
     * Catches settlements orphaned by a hard crash (kill, OOM, node failure) between
     * {@code SettlementTransactions.createPendingSettlement} committing (settlement {@code PENDING},
     * idempotency key {@code IN_PROGRESS}) and {@code finalizeSettlement} ever running. Such a
     * settlement never gets an externalRef -- only {@code finalizeSettlement} sets one -- so it's
     * invisible to {@code ReconciliationService.runOnce} (which only looks at
     * {@code findByExternalRefIsNotNull}), and its idempotency key stays {@code IN_PROGRESS}
     * forever, 409-ing every retry via {@code SettlementInProgressException}. Unlike the two
     * concurrency races {@code SettlementService} already retries, a hard crash leaves no live
     * thread to catch anything, so this has to be a separate sweep. Not wrapped in one transaction,
     * and one settlement's failure doesn't stop the others, for the same reasons as
     * {@code ReconciliationService.runOnce} (see reconciliation.md).
     */
    public void runOnce() {
        List<Settlement> candidates;
        try {
            candidates = settlementRepository.findByStatusAndExternalRefIsNullAndUpdatedAtBefore(
                    SettlementStatus.PENDING, Instant.now().minus(gracePeriod));
        } catch (RuntimeException e) {
            log.error("Stale-pending settlement sweep failed to load candidate settlements", e);
            return;
        }

        for (Settlement settlement : candidates) {
            try {
                finalizeAsUnknown(settlement);
            } catch (Exception e) {
                log.error("Stale-pending settlement sweep failed to finalize settlement {}, skipping",
                        settlement.getId(), e);
            }
        }
    }

    private void finalizeAsUnknown(Settlement settlement) {
        try {
            // Reuses the exact same UNKNOWN semantics SettlementService already falls back to on a
            // real gateway exception or exhausted finalize retries: "we don't know for certain,
            // reconciliation resolves it later" -- not a new state-machine path.
            settlementTransactions.finalizeSettlement(settlement.getId(),
                    new GatewayResult(SettlementOutcome.UNKNOWN, null));
        } catch (IllegalStateTransitionException alreadyResolved) {
            // Resolved by another process (an overlapping sweep run, or the original request thread
            // actually survived and finished) between when this run fetched it and now; nothing
            // left to do.
        }
    }
}
