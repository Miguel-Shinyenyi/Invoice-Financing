package com.settlementengine.core.events;

public final class KafkaTopics {

    public static final String SETTLEMENT_REQUESTED = "settlement.requested";
    public static final String SETTLEMENT_CONFIRMED = "settlement.confirmed";
    public static final String SETTLEMENT_FAILED = "settlement.failed";
    public static final String SETTLEMENT_UNKNOWN = "settlement.unknown";
    public static final String RECONCILIATION_MISMATCH_FOUND = "reconciliation.mismatch_found";
    public static final String RECONCILIATION_RESOLVED = "reconciliation.resolved";

    private KafkaTopics() {
    }
}
