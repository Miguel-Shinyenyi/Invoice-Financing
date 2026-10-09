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
@Table(name = "advances")
public class Advance {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "amount_advanced", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountAdvanced;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fee;

    @Column(name = "disbursed_settlement_id", nullable = false)
    private UUID disbursedSettlementId;

    @Column(name = "repaid_settlement_id")
    private UUID repaidSettlementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdvanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Advance() {
    }

    public Advance(UUID id, UUID invoiceId, BigDecimal amountAdvanced, BigDecimal fee, UUID disbursedSettlementId) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.amountAdvanced = amountAdvanced;
        this.fee = fee;
        this.disbursedSettlementId = disbursedSettlementId;
        this.status = AdvanceStatus.DISBURSED;
        this.createdAt = Instant.now();
    }

    public void markRepaid(UUID repaidSettlementId) {
        if (!status.canTransitionTo(AdvanceStatus.REPAID)) {
            throw new AdvanceTransitionException(status, AdvanceStatus.REPAID);
        }
        this.repaidSettlementId = repaidSettlementId;
        this.status = AdvanceStatus.REPAID;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getAmountAdvanced() {
        return amountAdvanced;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public UUID getDisbursedSettlementId() {
        return disbursedSettlementId;
    }

    public UUID getRepaidSettlementId() {
        return repaidSettlementId;
    }

    public AdvanceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
