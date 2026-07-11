package com.settlementengine.core.gateway;

public interface ExternalSettlementGateway {

    SettlementOutcome execute(SettlementExecutionRequest request);
}
