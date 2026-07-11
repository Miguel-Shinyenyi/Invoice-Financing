package com.settlementengine.core.gateway;

public record GatewayResult(SettlementOutcome outcome, String externalRef) {
}
