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
@Table(name = "invoices")
public class Invoice {

    @Id
    private UUID id;

    @Column(name = "business_account_id", nullable = false)
    private UUID businessAccountId;

    @Column(name = "customer_reference", nullable = false)
    private String customerReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(name = "external_source_ref", nullable = false, unique = true)
    private String externalSourceRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Invoice() {
    }

    public Invoice(UUID id, UUID businessAccountId, String customerReference, BigDecimal amount, String currency,
                   Instant dueDate, String externalSourceRef) {
        this.id = id;
        this.businessAccountId = businessAccountId;
        this.customerReference = customerReference;
        this.amount = amount;
        this.currency = currency;
        this.dueDate = dueDate;
        this.externalSourceRef = externalSourceRef;
        this.status = InvoiceStatus.ISSUED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transitionTo(InvoiceStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvoiceTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getDueDate() {
        return dueDate;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public String getExternalSourceRef() {
        return externalSourceRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
