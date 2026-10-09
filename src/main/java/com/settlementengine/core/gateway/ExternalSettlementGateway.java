package com.settlementengine.core.gateway;

public interface ExternalSettlementGateway {

    GatewayResult execute(SettlementExecutionRequest request);
}
