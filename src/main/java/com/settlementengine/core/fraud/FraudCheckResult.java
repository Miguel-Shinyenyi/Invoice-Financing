package com.settlementengine.core.fraud;

import com.settlementengine.core.invoicing.FraudDecision;

import java.math.BigDecimal;
import java.util.List;

public record FraudCheckResult(BigDecimal score, FraudDecision decision, List<String> reasons) {

    public static FraudCheckResult allowNoSignal() {
        return new FraudCheckResult(BigDecimal.ZERO, FraudDecision.ALLOW, List.of());
    }
}
