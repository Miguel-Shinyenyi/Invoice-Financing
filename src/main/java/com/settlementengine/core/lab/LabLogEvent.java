package com.settlementengine.core.lab;

import java.time.Instant;

/** One structured log line, from either service. {@code seq} is assigned by the buffer. */
public record LabLogEvent(
        long seq,
        Instant timestamp,
        String service,
        String level,
        String logger,
        String message,
        String requestId,
        String settlementId,
        String invoiceId,
        String traceId) {

    public LabLogEvent withSeq(long newSeq) {
        return new LabLogEvent(newSeq, timestamp, service, level, logger, message, requestId, settlementId,
                invoiceId, traceId);
    }
}
