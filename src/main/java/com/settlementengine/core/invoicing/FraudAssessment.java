package com.settlementengine.core.invoicing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_assessments")
public class FraudAssessment {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudDecision decision;

    @Column(columnDefinition = "TEXT")
    private String reasons;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FraudAssessment() {
    }

    public FraudAssessment(UUID id, UUID invoiceId, BigDecimal score, FraudDecision decision, String reasons) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.score = score;
        this.decision = decision;
        this.reasons = reasons;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public FraudDecision getDecision() {
        return decision;
    }

    public String getReasons() {
        return reasons;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
