package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bridges to the ml-service's lab routes (which exist only when it runs with ML_LAB=1): merges its logs into the
 * backend buffer, and forwards a time-boxed fault. A fault boundary under safety rule 7: it makes the service
 * unavailable from the outside, the engine's logic is untouched, and the backend's fraud client fails open.
 */
@LabComponent
public class LabMlService {

    static final int MAX_FAULT_SECONDS = 60;

    private final LabProperties props;
    private final LabLogBuffer buffer;
    private final LongSupplier nanoClock;
    private final LabUpstreamFetcher fetcher = new LabUpstreamFetcher(List.of("/lab/logs", "/lab/fault"));
    private long lastSeq;
    private Long lastFaultNanos;

    @Autowired
    public LabMlService(LabProperties props, LabLogBuffer buffer) {
        this(props, buffer, System::nanoTime);
    }

    LabMlService(LabProperties props, LabLogBuffer buffer, LongSupplier nanoClock) {
        this.props = props;
        this.buffer = buffer;
        this.nanoClock = nanoClock;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public synchronized void pollLogs() {
        try {
            JsonNode events = fetcher.get(props.upstreams().mlUrl(), "/lab/logs", Map.of("limit", "200")).path("events");
            long maxSeq = lastSeq;
            long highest = 0;
            for (JsonNode e : events) {
                highest = Math.max(highest, e.path("seq").asLong());
            }
            if (highest < lastSeq) {
                lastSeq = 0; // the ml-service restarted and its sequence started over
            }
            for (JsonNode e : events) {
                long seq = e.path("seq").asLong();
                if (seq <= lastSeq) {
                    continue;
                }
                maxSeq = Math.max(maxSeq, seq);
                if (isOwnPoll(e)) {
                    continue; // our own GET /lab/logs would otherwise be merged back in every 2 seconds
                }
                buffer.append(new LabLogEvent(0, OffsetDateTime.parse(e.path("timestamp").asText()).toInstant(),
                        e.path("service").asText("ml-service"), e.path("level").asText(), e.path("logger").asText(),
                        e.path("message").asText(), textOrNull(e, "requestId"), null, null, textOrNull(e, "traceId")));
            }
            lastSeq = Math.max(maxSeq, highest);
        } catch (RuntimeException e) {
            // ml-service not running with ML_LAB=1, or down: nothing to merge
        }
    }

    public synchronized Map<String, Object> startFault(int seconds) {
        if (seconds < 1 || seconds > MAX_FAULT_SECONDS) {
            throw new LabValidationException("seconds must be between 1 and " + MAX_FAULT_SECONDS);
        }
        long cooldownNanos = Duration.ofSeconds(props.upstreams().mlFaultCooldownSeconds()).toNanos();
        long now = nanoClock.getAsLong();
        if (lastFaultNanos != null && now - lastFaultNanos < cooldownNanos) {
            throw new LabBusyException("The ML fault was just used; wait " + props.upstreams().mlFaultCooldownSeconds()
                    + " seconds between faults.");
        }
        fetcher.postJson(props.upstreams().mlUrl(), "/lab/fault", "{\"seconds\":" + seconds + "}");
        lastFaultNanos = now;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seconds", seconds);
        out.put("expiresAt", Instant.now().plusSeconds(seconds));
        out.put("effect", "ml-service answers 503 on /score and /metrics until it expires; the backend fails open "
                + "(financing continues, fraud signal degraded) and MLServiceDown starts counting.");
        return out;
    }

    private static boolean isOwnPoll(JsonNode e) {
        return "uvicorn.access".equals(e.path("logger").asText()) && e.path("message").asText().contains("GET /lab/logs");
    }

    private static String textOrNull(JsonNode e, String field) {
        JsonNode v = e.path(field);
        return v.isNull() || v.isMissingNode() || v.asText().isBlank() ? null : v.asText();
    }
}
