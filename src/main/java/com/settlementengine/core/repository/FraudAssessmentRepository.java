package com.settlementengine.core.repository;

import com.settlementengine.core.invoicing.FraudAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FraudAssessmentRepository extends JpaRepository<FraudAssessment, UUID> {

    List<FraudAssessment> findByInvoiceId(UUID invoiceId);
}
