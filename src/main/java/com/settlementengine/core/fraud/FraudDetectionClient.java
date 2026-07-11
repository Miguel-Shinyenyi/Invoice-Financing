package com.settlementengine.core.fraud;

public interface FraudDetectionClient {

    FraudCheckResult check(FraudCheckRequest request);
}
