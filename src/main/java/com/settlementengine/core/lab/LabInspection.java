package com.settlementengine.core.lab;

import java.util.List;
import java.util.Map;

/** Everything the engine holds about one settlement. Read-only. */
public record LabInspection(
        Map<String, Object> settlement,
        Map<String, Object> idempotencyKey,
        List<Map<String, Object>> ledgerEntries,
        List<Map<String, Object>> outboxEvents,
        List<Map<String, Object>> mismatches,
        List<Map<String, Object>> ledgerMismatches,
        List<Map<String, Object>> audit,
        List<LabLogEvent> logs,
        List<String> traceIds,
        ExternalView external) {

    /** What the external system holds for this settlement. */
    public record ExternalView(String externalRef, boolean recordHeld, String orphanedExternalRef) {
    }
}
