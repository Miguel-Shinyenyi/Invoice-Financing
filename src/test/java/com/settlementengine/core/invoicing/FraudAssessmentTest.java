package com.settlementengine.core.invoicing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudAssessmentTest {

    @Test
    void storesAssessmentDetails() {
        UUID id = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();

        FraudAssessment assessment = new FraudAssessment(id, invoiceId, new BigDecimal("0.700"),
                FraudDecision.BLOCK, "duplicate_customer_reference, new_account_high_advance");

        assertThat(assessment.getId()).isEqualTo(id);
        assertThat(assessment.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(assessment.getScore()).isEqualByComparingTo("0.700");
        assertThat(assessment.getDecision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(assessment.getReasons()).isEqualTo("duplicate_customer_reference, new_account_high_advance");
        assertThat(assessment.getCreatedAt()).isNotNull();
    }

    @Test
    void allowsNullReasonsWhenNothingFlagged() {
        FraudAssessment assessment = new FraudAssessment(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO,
                FraudDecision.ALLOW, null);

        assertThat(assessment.getReasons()).isNull();
    }
}
