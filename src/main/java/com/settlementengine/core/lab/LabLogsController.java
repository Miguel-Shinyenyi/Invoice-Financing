package com.settlementengine.core.lab;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@LabController
@RequestMapping("/lab/logs")
public class LabLogsController {

    static final int MAX_LIMIT = 500;

    private final LabLogBuffer buffer;
    private final LabSseSupport sse;

    public LabLogsController(LabLogBuffer buffer, LabSseSupport sse) {
        this.buffer = buffer;
        this.sse = sse;
    }

    @GetMapping
    public Map<String, Object> logs(@RequestParam(required = false) String level,
                                    @RequestParam(required = false) String loggerPrefix,
                                    @RequestParam(required = false) String requestId,
                                    @RequestParam(required = false) String settlementId,
                                    @RequestParam(required = false) String text,
                                    @RequestParam(required = false) String since,
                                    @RequestParam(required = false) String service,
                                    @RequestParam(defaultValue = "200") int limit,
                                    @RequestParam(required = false) Long after) {
        LabLogFilter filter = filter(level, loggerPrefix, requestId, settlementId, text, since, service);
        int capped = Math.max(1, Math.min(MAX_LIMIT, limit));
        List<LabLogEvent> events = after == null ? buffer.query(filter, capped) : buffer.after(after, filter, capped);
        return Map.of("events", events, "capacity", buffer.capacity(), "size", buffer.size());
    }

    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam(required = false) String level,
                             @RequestParam(required = false) String loggerPrefix,
                             @RequestParam(required = false) String requestId,
                             @RequestParam(required = false) String settlementId,
                             @RequestParam(required = false) String text,
                             @RequestParam(required = false) String service,
                             HttpServletRequest request) {
        LabLogFilter filter = filter(level, loggerPrefix, requestId, settlementId, text, null, service);
        return sse.open(request, emitter -> buffer.subscribe(event -> {
            if (!filter.matches(event)) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("log").id(Long.toString(event.seq())).data(event));
            } catch (IOException | IllegalStateException e) {
                emitter.complete();
            }
        }));
    }

    private static LabLogFilter filter(String level, String loggerPrefix, String requestId, String settlementId,
                                       String text, String since, String service) {
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (java.time.format.DateTimeParseException e) {
                throw new LabValidationException("since must be an ISO-8601 instant, e.g. 2026-10-09T12:00:00Z");
            }
        }
        return new LabLogFilter(level, loggerPrefix, requestId, settlementId, text, sinceInstant, service);
    }
}
