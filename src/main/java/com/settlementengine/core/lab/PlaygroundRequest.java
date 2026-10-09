package com.settlementengine.core.lab;

import java.math.BigDecimal;
import java.util.UUID;

public record PlaygroundRequest(UUID idempotencyKey, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                                String currency, FaultRequest fault) {

    public record FaultRequest(LabFault mode, Integer slowMs) {
    }
}
