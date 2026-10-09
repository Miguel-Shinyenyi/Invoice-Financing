package com.settlementengine.core.lab;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP for rate limits. X-Forwarded-For is a spoofable header, so it is honoured only when configured. */
final class ClientIp {

    private ClientIp() {
    }

    static String of(HttpServletRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
