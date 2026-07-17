package com.settlementengine.core.readmodel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SettlementReadModelRepository extends JpaRepository<SettlementReadModel, UUID> {

    /**
     * ownerId null (ADMIN/SUPPORT) returns every settlement; non-null (READ_ONLY) restricts to
     * settlements touching an account that owner owns. The read model itself doesn't store
     * owner_id, so ownership is resolved via a join against LedgerAccount at query time.
     */
    @Query("select s from SettlementReadModel s where "
            + "(:status is null or s.status = :status) and "
            + "(:ownerId is null or "
            + " s.sourceAccountId in (select a.id from LedgerAccount a where a.ownerId = :ownerId) or "
            + " s.destinationAccountId in (select a.id from LedgerAccount a where a.ownerId = :ownerId))")
    Page<SettlementReadModel> findVisible(@Param("ownerId") UUID ownerId, @Param("status") String status, Pageable pageable);

    Page<SettlementReadModel> findBySourceAccountIdOrDestinationAccountId(
            UUID sourceAccountId, UUID destinationAccountId, Pageable pageable);
}
