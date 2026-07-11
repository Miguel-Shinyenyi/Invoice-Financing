package com.settlementengine.core.gateway;

import org.springframework.stereotype.Component;

@Component
public class MockExternalSettlementGateway implements ExternalSettlementGateway {

    @Override
    public SettlementOutcome execute(SettlementExecutionRequest request) {
        return SettlementOutcome.CONFIRMED;
    }
}
