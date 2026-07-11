package com.settlementengine.core.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public record SettlementExecutionRequest(
        UUID settlementId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency) {
}
