package com.settlementengine.core.events;

public final class KafkaTopics {

    public static final String SETTLEMENT_REQUESTED = "settlement.requested";
    public static final String SETTLEMENT_CONFIRMED = "settlement.confirmed";
    public static final String SETTLEMENT_FAILED = "settlement.failed";
    public static final String SETTLEMENT_UNKNOWN = "settlement.unknown";

    private KafkaTopics() {
    }
}
