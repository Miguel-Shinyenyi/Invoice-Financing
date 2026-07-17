package com.settlementengine.core.repository;

import com.settlementengine.core.invoicing.Invoice;
import com.settlementengine.core.invoicing.InvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByStatusIn(List<InvoiceStatus> statuses);

    /**
     * Locks the row for the duration of the caller's transaction. Used to atomically claim an
     * invoice for financing (check ISSUED, transition to FINANCED) before the disbursement
     * settlement is even attempted, so two concurrent finance requests for the same invoice with
     * different idempotency keys can't both succeed -- the settlement engine's own idempotency
     * mechanism only protects retries of the *same* key, not this business-level race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

    long countByCustomerReferenceAndBusinessAccountIdNot(String customerReference, UUID businessAccountId);

    long countByBusinessAccountIdAndStatusIn(UUID businessAccountId, List<InvoiceStatus> statuses);

    /**
     * ownerId null (ADMIN/SUPPORT) returns every invoice; non-null (READ_ONLY) restricts to
     * invoices whose business account that owner owns.
     */
    @Query("select i from Invoice i where "
            + "(:status is null or i.status = :status) and "
            + "(:ownerId is null or i.businessAccountId in (select a.id from LedgerAccount a where a.ownerId = :ownerId))")
    Page<Invoice> findVisible(@Param("ownerId") UUID ownerId, @Param("status") InvoiceStatus status, Pageable pageable);
}
