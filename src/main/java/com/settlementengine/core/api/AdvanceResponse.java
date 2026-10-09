package com.settlementengine.core.api;

import com.settlementengine.core.invoicing.Advance;
import com.settlementengine.core.invoicing.AdvanceStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record AdvanceResponse(
        UUID id,
        BigDecimal amountAdvanced,
        BigDecimal fee,
        UUID disbursedSettlementId,
        UUID repaidSettlementId,
        AdvanceStatus status) {

    public static AdvanceResponse from(Advance advance) {
        return new AdvanceResponse(advance.getId(), advance.getAmountAdvanced(), advance.getFee(),
                advance.getDisbursedSettlementId(), advance.getRepaidSettlementId(), advance.getStatus());
    }
}
