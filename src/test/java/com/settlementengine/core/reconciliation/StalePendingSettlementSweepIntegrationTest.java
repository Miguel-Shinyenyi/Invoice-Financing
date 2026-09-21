package com.settlementengine.core.reconciliation;

import com.settlementengine.core.AbstractIntegrationTest;
import com.settlementengine.core.domain.IdempotencyKey;
import com.settlementengine.core.domain.IdempotencyKeyStatus;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.repository.IdempotencyKeyRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.SettlementRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.RequestHasher;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Zero grace period: this test reproduces a crash that already happened, not one that's still
// within its "maybe the external call just hasn't been recorded yet" window, so there's nothing
// to gain from waiting out the default 300s here.
@TestPropertySource(properties = "settlement-engine.reconciliation.stale-pending-grace-period-seconds=0")
class StalePendingSettlementSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StalePendingSettlementSweepService stalePendingSettlementSweepService;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private RequestHasher requestHasher;

    private LedgerAccount account(String balance) {
        return ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(balance), "USD"));
    }

    @Test
    void resolvesAStalePendingSettlementWithNoExternalRefToUnknownAndUnblocksFutureRetries() {
        LedgerAccount source = account("500.00");
        LedgerAccount destination = account("0.00");
        CreateSettlementCommand command = new CreateSettlementCommand(
                source.getId(), destination.getId(), new BigDecimal("50.00"), "USD");

        // Reproduces a hard crash between SettlementTransactions.createPendingSettlement
        // committing and finalizeSettlement ever running: a PENDING settlement with no
        // externalRef (only finalizeSettlement ever sets one) and its idempotency key still
        // IN_PROGRESS, as if the process died before the gateway call ever completed.
        UUID idempotencyKeyId = UUID.randomUUID();
        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKeyId, requestHasher.hash(command)));

        UUID settlementId = UUID.randomUUID();
        Settlement orphaned = new Settlement(settlementId, idempotencyKeyId, source.getId(), destination.getId(),
                new BigDecimal("50.00"), "USD");
        settlementRepository.save(orphaned);

        assertThat(settlementRepository.findById(settlementId).orElseThrow().getExternalRef()).isNull();
        assertThat(idempotencyKeyRepository.findById(idempotencyKeyId).orElseThrow().getStatus())
                .isEqualTo(IdempotencyKeyStatus.IN_PROGRESS);

        // Before the fix, this settlement is invisible to ReconciliationService.runOnce (which
        // only looks at findByExternalRefIsNotNull) and its idempotency key never leaves
        // IN_PROGRESS -- every retry with this key 409s forever.
        stalePendingSettlementSweepService.runOnce();

        Settlement resolved = settlementRepository.findById(settlementId).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(SettlementStatus.UNKNOWN);

        IdempotencyKey key = idempotencyKeyRepository.findById(idempotencyKeyId).orElseThrow();
        assertThat(key.getStatus()).isEqualTo(IdempotencyKeyStatus.COMPLETED);

        // Confirms retries are actually unblocked, not just that the key flipped state: the same
        // key with the same request payload now returns the cached (UNKNOWN) result instead of
        // throwing SettlementInProgressException.
        SettlementResult retryResult = settlementService.createSettlement(idempotencyKeyId, command);
        assertThat(retryResult.settlementId()).isEqualTo(settlementId);
        assertThat(retryResult.status()).isEqualTo(SettlementStatus.UNKNOWN);
    }
}
