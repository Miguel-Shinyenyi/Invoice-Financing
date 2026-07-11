package com.settlementengine.core.fraud;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

public record FraudCheckRequest(@JsonProperty("invoice_id") UUID invoiceId,
                                 @JsonProperty("business_account_id") UUID businessAccountId,
                                 @JsonProperty("customer_reference") String customerReference,
                                 @JsonProperty("amount") BigDecimal amount,
                                 @JsonProperty("currency") String currency,
                                 @JsonProperty("requested_advance_amount") BigDecimal requestedAdvanceAmount,
                                 @JsonProperty("business_account_age_days") long businessAccountAgeDays,
                                 @JsonProperty("duplicate_customer_reference_count") long duplicateCustomerReferenceCount,
                                 @JsonProperty("outstanding_advance_count") long outstandingAdvanceCount) {
}
