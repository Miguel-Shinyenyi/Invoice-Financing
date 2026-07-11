package com.settlementengine.core.gateway;

import com.settlementengine.core.reconciliation.ExternalRecord;
import com.settlementengine.core.reconciliation.ExternalReconciliationSource;
import com.settlementengine.core.reconciliation.ExternalStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for a real external system (bank feed, payment rail) until one is integrated. It
 * plays both roles a real external system would: confirming settlements when asked
 * ({@link ExternalSettlementGateway}) and answering "what actually happened" when reconciliation
 * asks later ({@link ExternalReconciliationSource}) — the same in-memory record backs both, so by
 * default everything reconciles cleanly. {@link #forget} and {@link #corrupt} let tests and demos
 * simulate the external system disagreeing with us, which is the whole reason the reconciliation
 * engine exists.
 */
@Component
public class MockExternalSystem implements ExternalSettlementGateway, ExternalReconciliationSource {

    private final Map<String, ExternalRecord> records = new ConcurrentHashMap<>();

    @Override
    public GatewayResult execute(SettlementExecutionRequest request) {
        String externalRef = "MOCK-" + UUID.randomUUID();
        records.put(externalRef, new ExternalRecord(externalRef, request.amount(), request.currency(),
                ExternalStatus.CONFIRMED));
        return new GatewayResult(SettlementOutcome.CONFIRMED, externalRef);
    }

    @Override
    public Optional<ExternalRecord> findByReference(String reference) {
        return Optional.ofNullable(records.get(reference));
    }

    public void forget(String reference) {
        records.remove(reference);
    }

    public void corrupt(String reference, ExternalRecord replacement) {
        records.put(reference, replacement);
    }
}
