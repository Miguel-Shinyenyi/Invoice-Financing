package com.settlementengine.core.reconciliation;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.gateway.MockExternalSystem;
import com.settlementengine.core.repository.IdempotencyKeyRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.ReconciliationMismatchRepository;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired
    private ReconciliationMismatchRepository reconciliationMismatchRepository;
    @Autowired
    private MockExternalSystem mockExternalSystem;

    private LedgerAccount account(String balance) {
        return ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(balance), "USD"));
    }

    @Test
    void resolvesAnUnknownSettlementWhenExternalRecordConfirmsIt() {
        LedgerAccount source = account("500.00");
        LedgerAccount destination = account("0.00");

        UUID idempotencyKeyId = UUID.randomUUID();
        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKeyId, "test-hash"));

        UUID settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, idempotencyKeyId, source.getId(), destination.getId(),
                new BigDecimal("50.00"), "USD");
        settlement.transitionTo(SettlementStatus.UNKNOWN);
        settlement.setExternalRef("integration-test-ref-1");
        settlementRepository.save(settlement);

        mockExternalSystem.corrupt("integration-test-ref-1",
                new com.settlementengine.core.reconciliation.ExternalRecord("integration-test-ref-1",
                        new BigDecimal("50.00"), "USD", ExternalStatus.CONFIRMED));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        // Scoped to this settlement, not the run's global count: other test methods share this
        // Spring context/database and may have left their own (unrelated) mismatches behind.
        assertThat(reconciliationMismatchRepository.findBySettlementIdAndResolutionStatus(
                settlementId, MismatchResolutionStatus.OPEN)).isEmpty();

        Settlement reloaded = settlementRepository.findById(settlementId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);

        LedgerAccount refreshedSource = ledgerAccountRepository.findById(source.getId()).orElseThrow();
        LedgerAccount refreshedDestination = ledgerAccountRepository.findById(destination.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo("450.00");
        assertThat(refreshedDestination.getBalance()).isEqualByComparingTo("50.00");
        assertThat(ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getSettlementId().equals(settlementId)).count()).isEqualTo(2);
    }

    @Test
    void flagsAMismatchWhenExternalSystemDisagreesWithAConfirmedSettlement() {
        LedgerAccount source = account("500.00");
        LedgerAccount destination = account("0.00");
        CreateSettlementCommand command = new CreateSettlementCommand(
                source.getId(), destination.getId(), new BigDecimal("30.00"), "USD");

        SettlementResult result = settlementService.createSettlement(UUID.randomUUID(), command);
        assertThat(result.status()).isEqualTo(SettlementStatus.CONFIRMED);

        String externalRef = settlementRepository.findById(result.settlementId()).orElseThrow().getExternalRef();
        mockExternalSystem.corrupt(externalRef, new com.settlementengine.core.reconciliation.ExternalRecord(
                externalRef, new BigDecimal("30.00"), "USD", ExternalStatus.FAILED));

        ReconciliationRun run = reconciliationService.runOnce();

        assertThat(run.getMismatchesFound()).isGreaterThanOrEqualTo(1);

        Optional<ReconciliationMismatch> mismatch = reconciliationMismatchRepository
                .findBySettlementIdAndResolutionStatus(result.settlementId(), MismatchResolutionStatus.OPEN);
        assertThat(mismatch).isPresent();
        assertThat(mismatch.get().getInternalState()).isEqualTo("CONFIRMED");
        assertThat(mismatch.get().getExternalState()).isEqualTo("FAILED");

        // Not auto-resolved: a CONFIRMED settlement never gets silently overwritten.
        Settlement stillConfirmed = settlementRepository.findById(result.settlementId()).orElseThrow();
        assertThat(stillConfirmed.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);

        List<ReconciliationMismatch> open = reconciliationMismatchRepository
                .findByResolutionStatus(MismatchResolutionStatus.OPEN);
        assertThat(open).anyMatch(m -> m.getSettlementId().equals(result.settlementId()));

        mismatch.get().resolve("Confirmed with provider: false alarm from a delayed webhook");
        reconciliationMismatchRepository.save(mismatch.get());
        assertThat(reconciliationMismatchRepository.findBySettlementIdAndResolutionStatus(
                result.settlementId(), MismatchResolutionStatus.OPEN)).isEmpty();
    }
}
