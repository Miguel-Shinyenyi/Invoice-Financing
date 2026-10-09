package com.settlementengine.core.reconciliation;

import com.settlementengine.core.domain.IllegalStateTransitionException;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.events.KafkaTopics;
import com.settlementengine.core.events.ReconciliationMismatchFoundEvent;
import com.settlementengine.core.events.ReconciliationResolvedEvent;
import com.settlementengine.core.gateway.GatewayResult;
import com.settlementengine.core.gateway.SettlementOutcome;
import com.settlementengine.core.outbox.OutboxWriter;
import com.settlementengine.core.repository.ReconciliationMismatchRepository;
import com.settlementengine.core.repository.ReconciliationRunRepository;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.SettlementTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String AGGREGATE_TYPE_SETTLEMENT = "SETTLEMENT";
    private static final String AGGREGATE_TYPE_MISMATCH = "RECONCILIATION_MISMATCH";

    private final SettlementRepository settlementRepository;
    private final ExternalReconciliationSource externalReconciliationSource;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationMismatchRepository reconciliationMismatchRepository;
    private final SettlementTransactions settlementTransactions;
    private final OutboxWriter outboxWriter;
    private final Duration gracePeriod;

    public ReconciliationService(SettlementRepository settlementRepository,
                                  ExternalReconciliationSource externalReconciliationSource,
                                  ReconciliationRunRepository reconciliationRunRepository,
                                  ReconciliationMismatchRepository reconciliationMismatchRepository,
                                  SettlementTransactions settlementTransactions,
                                  OutboxWriter outboxWriter,
                                  @Value("${settlement-engine.reconciliation.grace-period-seconds:300}") long gracePeriodSeconds) {
        this.settlementRepository = settlementRepository;
        this.externalReconciliationSource = externalReconciliationSource;
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.reconciliationMismatchRepository = reconciliationMismatchRepository;
        this.settlementTransactions = settlementTransactions;
        this.outboxWriter = outboxWriter;
        this.gracePeriod = Duration.ofSeconds(gracePeriodSeconds);
    }

    /**
     * Checks every settlement the gateway has ever produced an external reference for against the
     * external system's own record of truth. Deliberately not wrapped in one big transaction: each
     * settlement's resolution (via {@link SettlementTransactions#finalizeSettlement}) or mismatch
     * write is already its own atomic unit, and a long-running transaction spanning every candidate
     * settlement would hold resources far longer than any single write needs to. One settlement
     * erroring out is logged and skipped rather than aborting the whole run.
     */
    public ReconciliationRun runOnce() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), Instant.now());
        reconciliationRunRepository.save(run);

        List<Settlement> candidates;
        try {
            candidates = settlementRepository.findByExternalRefIsNotNull();
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed to load candidate settlements", run.getId(), e);
            run.fail();
            reconciliationRunRepository.save(run);
            return run;
        }

        int mismatches = 0;
        for (Settlement settlement : candidates) {
            try {
                if (reconcileOne(run, settlement)) {
                    mismatches++;
                }
            } catch (Exception e) {
                log.error("Reconciliation run {} failed to check settlement {}, skipping",
                        run.getId(), settlement.getId(), e);
            }
        }

        run.complete(candidates.size(), mismatches);
        reconciliationRunRepository.save(run);
        return run;
    }

    private boolean reconcileOne(ReconciliationRun run, Settlement settlement) {
        Optional<ExternalRecord> externalRecordOpt = externalReconciliationSource.findByReference(settlement.getExternalRef());

        if (externalRecordOpt.isEmpty()) {
            if (Instant.now().isBefore(settlement.getUpdatedAt().plus(gracePeriod))) {
                return false;
            }
            return flagMismatch(run, settlement, settlement.getStatus().name(), null,
                    "No external record found for reference " + settlement.getExternalRef() + " after grace period");
        }

        ExternalRecord externalRecord = externalRecordOpt.get();
        boolean amountMatches = externalRecord.amount().compareTo(settlement.getAmount()) == 0
                && externalRecord.currency().equals(settlement.getCurrency());
        if (!amountMatches) {
            return flagMismatch(run, settlement, settlement.getStatus().name(), externalRecord.status().name(),
                    "External record amount/currency (%s %s) does not match internal (%s %s)".formatted(
                            externalRecord.amount(), externalRecord.currency(), settlement.getAmount(), settlement.getCurrency()));
        }

        if (settlement.getStatus() == SettlementStatus.UNKNOWN) {
            return autoResolveUnknown(run, settlement, externalRecord);
        }

        boolean statusAgrees = (settlement.getStatus() == SettlementStatus.CONFIRMED && externalRecord.status() == ExternalStatus.CONFIRMED)
                || (settlement.getStatus() == SettlementStatus.FAILED && externalRecord.status() == ExternalStatus.FAILED);
        if (statusAgrees) {
            return false;
        }

        return flagMismatch(run, settlement, settlement.getStatus().name(), externalRecord.status().name(),
                "Internal status %s does not match external status %s".formatted(settlement.getStatus(), externalRecord.status()));
    }

    private boolean autoResolveUnknown(ReconciliationRun run, Settlement settlement, ExternalRecord externalRecord) {
        SettlementOutcome resolvedOutcome = externalRecord.status() == ExternalStatus.CONFIRMED
                ? SettlementOutcome.CONFIRMED : SettlementOutcome.FAILED;
        try {
            settlementTransactions.finalizeSettlement(settlement.getId(),
                    new GatewayResult(resolvedOutcome, settlement.getExternalRef()));
        } catch (IllegalStateTransitionException alreadyResolved) {
            // Resolved by another process (e.g. a concurrent reconciliation run) between when this
            // run fetched it and now; nothing left to do.
            return false;
        }
        outboxWriter.write(AGGREGATE_TYPE_SETTLEMENT, settlement.getId(), KafkaTopics.RECONCILIATION_RESOLVED,
                new ReconciliationResolvedEvent(settlement.getId(),
                        "Auto-resolved from UNKNOWN to " + resolvedOutcome + " via reconciliation", Instant.now()));
        return false;
    }

    private boolean flagMismatch(ReconciliationRun run, Settlement settlement, String internalState,
                                  String externalState, String details) {
        Optional<ReconciliationMismatch> existing = reconciliationMismatchRepository
                .findBySettlementIdAndResolutionStatus(settlement.getId(), MismatchResolutionStatus.OPEN);
        if (existing.isPresent()) {
            return true;
        }
        ReconciliationMismatch mismatch = new ReconciliationMismatch(UUID.randomUUID(), run.getId(), settlement.getId(),
                internalState, externalState, details);
        reconciliationMismatchRepository.save(mismatch);
        outboxWriter.write(AGGREGATE_TYPE_MISMATCH, mismatch.getId(), KafkaTopics.RECONCILIATION_MISMATCH_FOUND,
                new ReconciliationMismatchFoundEvent(mismatch.getId(), run.getId(), settlement.getId(),
                        internalState, externalState, Instant.now()));
        return true;
    }
}
