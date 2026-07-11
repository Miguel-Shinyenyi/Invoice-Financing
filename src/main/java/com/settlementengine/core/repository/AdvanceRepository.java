package com.settlementengine.core.repository;

import com.settlementengine.core.invoicing.Advance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdvanceRepository extends JpaRepository<Advance, UUID> {

    Optional<Advance> findByInvoiceId(UUID invoiceId);
}
