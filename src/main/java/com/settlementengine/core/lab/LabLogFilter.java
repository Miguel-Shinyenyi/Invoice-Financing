package com.settlementengine.core.lab;

import java.time.Instant;
import java.util.List;

/** All fields optional. {@code level} is a minimum severity. */
public record LabLogFilter(String level, String loggerPrefix, String requestId, String settlementId, String text,
                           Instant since, String service) {

    public static final LabLogFilter NONE = new LabLogFilter(null, null, null, null, null, null, null);

    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    public boolean matches(LabLogEvent e) {
        if (level != null && !level.isBlank() && rank(e.level()) < rank(level)) {
            return false;
        }
        if (loggerPrefix != null && !loggerPrefix.isBlank()
                && (e.logger() == null || !e.logger().startsWith(loggerPrefix))) {
            return false;
        }
        if (requestId != null && !requestId.isBlank() && !requestId.equals(e.requestId())) {
            return false;
        }
        if (settlementId != null && !settlementId.isBlank() && !settlementId.equals(e.settlementId())) {
            return false;
        }
        if (text != null && !text.isBlank()
                && (e.message() == null || !e.message().toLowerCase().contains(text.toLowerCase()))) {
            return false;
        }
        if (since != null && e.timestamp().isBefore(since)) {
            return false;
        }
        if (service != null && !service.isBlank() && !service.equals(e.service())) {
            return false;
        }
        return true;
    }

    private static int rank(String l) {
        int i = l == null ? -1 : LEVELS.indexOf(l.toUpperCase());
        return i < 0 ? 0 : i;
    }
}
