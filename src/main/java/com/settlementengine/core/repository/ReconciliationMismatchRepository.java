package com.settlementengine.core.repository;

import com.settlementengine.core.reconciliation.MismatchResolutionStatus;
import com.settlementengine.core.reconciliation.ReconciliationMismatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationMismatchRepository extends JpaRepository<ReconciliationMismatch, UUID> {

    List<ReconciliationMismatch> findByResolutionStatus(MismatchResolutionStatus resolutionStatus);

    Optional<ReconciliationMismatch> findBySettlementIdAndResolutionStatus(UUID settlementId,
                                                                            MismatchResolutionStatus resolutionStatus);
}
