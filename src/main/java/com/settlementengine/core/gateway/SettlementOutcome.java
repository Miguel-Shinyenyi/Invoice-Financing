package com.settlementengine.core.gateway;

import com.settlementengine.core.domain.SettlementStatus;

public enum SettlementOutcome {
    CONFIRMED,
    FAILED,
    UNKNOWN;

    public SettlementStatus toSettlementStatus() {
        return switch (this) {
            case CONFIRMED -> SettlementStatus.CONFIRMED;
            case FAILED -> SettlementStatus.FAILED;
            case UNKNOWN -> SettlementStatus.UNKNOWN;
        };
    }
}
