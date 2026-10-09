package com.settlementengine.core.reconciliation;

import java.math.BigDecimal;

public record ExternalRecord(String reference, BigDecimal amount, String currency, ExternalStatus status) {
}
