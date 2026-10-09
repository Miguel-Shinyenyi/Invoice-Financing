package com.settlementengine.core.service;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateSettlementCommand(
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency) {
}
