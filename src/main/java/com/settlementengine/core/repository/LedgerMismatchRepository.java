package com.settlementengine.core.repository;

import com.settlementengine.core.reconciliation.LedgerMismatch;
import com.settlementengine.core.reconciliation.MismatchResolutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerMismatchRepository extends JpaRepository<LedgerMismatch, UUID> {

    List<LedgerMismatch> findByResolutionStatus(MismatchResolutionStatus resolutionStatus);

    Optional<LedgerMismatch> findByAccountIdAndResolutionStatus(UUID accountId,
                                                               MismatchResolutionStatus resolutionStatus);
}
