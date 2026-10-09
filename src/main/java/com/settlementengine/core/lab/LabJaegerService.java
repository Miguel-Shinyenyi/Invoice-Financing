package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only wrapper over Jaeger's query API. Two fixed endpoints only; the trace id must be hex and a request id
 * must be a plain token, so nothing a visitor types reaches Jaeger as anything but an encoded value.
 */
@LabComponent
public class LabJaegerService {

    static final String BACKEND_SERVICE = "settlement-engine-backend";
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{16}|[0-9a-f]{32}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_LIMIT = 20;

    public record SpanView(String spanId, String parentSpanId, String service, String operation, String kind,
                           long offsetMicros, long durationMicros, boolean error, Map<String, String> attributes) {
    }

    public record TraceView(String traceId, long startMicros, long durationMicros, int spanCount, List<String> services,
                            List<SpanView> spans) {
    }

    public record TraceSummary(String traceId, String rootOperation, List<String> services, int spanCount,
                               long durationMicros, long startMicros, boolean hasError) {
    }

    private final LabProperties props;
    private final LabLogBuffer logs;
    private final LabUpstreamFetcher fetcher = new LabUpstreamFetcher(List.of("/api/traces", "/api/traces/{id}"));

    public LabJaegerService(LabProperties props, LabLogBuffer logs) {
        this.props = props;
        this.logs = logs;
    }

    public TraceView trace(String traceId) {
        if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
            throw new LabValidationException("traceId must be 16 or 32 lowercase hex characters");
        }
        JsonNode data = fetcher.getTemplate(props.upstreams().jaegerUrl(), "/api/traces/{id}", traceId, Map.of()).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new LabNotFoundException("No trace " + traceId);
        }
        return view(data.get(0));
    }

    public List<TraceSummary> search(String requestId, String settlementId, String invoiceId, int limit) {
        int capped = Math.max(1, Math.min(MAX_LIMIT, limit));
        if (requestId != null && !requestId.isBlank() && !REQUEST_ID.matcher(requestId).matches()) {
            throw new LabValidationException("requestId may contain letters, digits, '.', '_' and '-' only");
        }
        requireUuid("settlementId", settlementId);
        requireUuid("invoiceId", invoiceId);
        boolean filtered = notBlank(requestId) || notBlank(settlementId) || notBlank(invoiceId);

        if (!filtered) {
            JsonNode data = fetcher.get(props.upstreams().jaegerUrl(), "/api/traces",
                    Map.of("service", BACKEND_SERVICE, "limit", Integer.toString(capped), "lookback", "1h")).path("data");
            List<TraceSummary> out = new ArrayList<>();
            for (JsonNode t : data) {
                out.add(summary(view(t)));
            }
            return out;
        }

        Set<String> traceIds = new LinkedHashSet<>();
        List<LabLogEvent> events = logs.query(new LabLogFilter(null, null, blankToNull(requestId), blankToNull(settlementId),
                null, null, null), 2000);
        for (int i = events.size() - 1; i >= 0 && traceIds.size() < capped; i--) {
            LabLogEvent e = events.get(i);
            if (notBlank(invoiceId) && !invoiceId.equals(e.invoiceId())) {
                continue;
            }
            if (e.traceId() != null && TRACE_ID.matcher(e.traceId()).matches()) {
                traceIds.add(e.traceId());
            }
        }
        List<TraceSummary> out = new ArrayList<>();
        RuntimeException lastFailure = null;
        for (String id : traceIds) {
            try {
                out.add(summary(trace(id)));
            } catch (LabNotFoundException e) {
                // not stored (yet, or evicted): skip it
            } catch (LabUpstreamUnavailableException e) {
                lastFailure = e;
            }
        }
        if (out.isEmpty() && lastFailure != null) {
            throw lastFailure;
        }
        return out;
    }

    // ------------------------------------------------------------------ parsing

    private TraceView view(JsonNode trace) {
        Map<String, String> serviceByProcess = new LinkedHashMap<>();
        trace.path("processes").fields().forEachRemaining(e -> serviceByProcess.put(e.getKey(), e.getValue().path("serviceName").asText()));
        long min = Long.MAX_VALUE;
        long max = 0;
        for (JsonNode s : trace.path("spans")) {
            min = Math.min(min, s.path("startTime").asLong());
            max = Math.max(max, s.path("startTime").asLong() + s.path("duration").asLong());
        }
        if (min == Long.MAX_VALUE) {
            min = 0;
        }
        List<SpanView> spans = new ArrayList<>();
        for (JsonNode s : trace.path("spans")) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (JsonNode tag : s.path("tags")) {
                attrs.put(tag.path("key").asText(), tag.path("value").asText());
            }
            String parent = null;
            for (JsonNode ref : s.path("references")) {
                if ("CHILD_OF".equals(ref.path("refType").asText())) {
                    parent = ref.path("spanID").asText();
                }
            }
            spans.add(new SpanView(s.path("spanID").asText(), parent, serviceByProcess.getOrDefault(s.path("processID").asText(), "?"),
                    s.path("operationName").asText(), kind(attrs), s.path("startTime").asLong() - min, s.path("duration").asLong(),
                    "true".equals(attrs.get("error")), attrs));
        }
        spans.sort(Comparator.comparingLong(SpanView::offsetMicros));
        List<String> services = spans.stream().map(SpanView::service).distinct().toList();
        return new TraceView(trace.path("traceID").asText(), min, max - min, spans.size(), services, spans);
    }

    private static String kind(Map<String, String> attrs) {
        if (attrs.containsKey("db.system") || attrs.containsKey("db.statement")) {
            return "sql";
        }
        if (attrs.containsKey("messaging.system")) {
            return "kafka".equals(attrs.get("messaging.system")) ? "kafka" : "messaging";
        }
        String spanKind = attrs.get("span.kind");
        boolean http = attrs.containsKey("http.request.method") || attrs.containsKey("http.method") || attrs.containsKey("url.full");
        if ("client".equals(spanKind) && http) {
            return "http-client";
        }
        if ("server".equals(spanKind)) {
            return "http-server";
        }
        return "internal";
    }

    private static TraceSummary summary(TraceView t) {
        String root = t.spans().stream().filter(s -> s.parentSpanId() == null).map(SpanView::operation).findFirst().orElse("?");
        return new TraceSummary(t.traceId(), root, t.services(), t.spanCount(), t.durationMicros(), t.startMicros(),
                t.spans().stream().anyMatch(SpanView::error));
    }

    private static void requireUuid(String name, String value) {
        if (notBlank(value)) {
            try {
                UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                throw new LabValidationException(name + " must be a UUID");
            }
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }
}
