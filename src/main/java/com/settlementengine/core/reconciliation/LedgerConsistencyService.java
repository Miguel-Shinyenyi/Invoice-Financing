package com.settlementengine.core.reconciliation;

import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerInconsistencyException;
import com.settlementengine.core.repository.LedgerEntryRepository;
import com.settlementengine.core.repository.LedgerMismatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Checks an account's stored balance against the net of its ledger entries. A disagreement is
 * recorded as an OPEN {@link LedgerMismatch} for manual review (at most one open per account) and
 * then always thrown, so a caller is never handed a balance the ledger doesn't back.
 *
 * <p>Deliberately not {@code @Transactional}: the mismatch row must commit even though the call
 * then throws, which an enclosing transaction would roll back.
 */
@Service
public class LedgerConsistencyService {

    private static final Logger log = LoggerFactory.getLogger(LedgerConsistencyService.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerMismatchRepository ledgerMismatchRepository;

    public LedgerConsistencyService(LedgerEntryRepository ledgerEntryRepository,
                                    LedgerMismatchRepository ledgerMismatchRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerMismatchRepository = ledgerMismatchRepository;
    }

    public void verify(LedgerAccount account) {
        BigDecimal computed = ledgerEntryRepository.sumNetByAccountId(account.getId());
        BigDecimal stored = account.getBalance();
        if (stored.compareTo(computed) == 0) {
            return;
        }

        if (ledgerMismatchRepository.findByAccountIdAndResolutionStatus(account.getId(),
                MismatchResolutionStatus.OPEN).isEmpty()) {
            ledgerMismatchRepository.save(new LedgerMismatch(UUID.randomUUID(), account.getId(), stored, computed,
                    "Stored balance " + stored + " does not match net of ledger entries " + computed));
            log.warn("Recorded ledger mismatch for account {}: stored {}, computed {}", account.getId(), stored,
                    computed);
        }
        throw new LedgerInconsistencyException(account.getId(), stored, computed);
    }
}
