package com.settlementengine.core.lab;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.List;

/**
 * All caps live here (config), never in the client. The UI reads them from {@code GET /lab/status}.
 */
@ConfigurationProperties(prefix = "settlement-engine.demo")
public record LabProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean readOnly,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue("107.155.122.29") List<String> forbiddenHosts,
        @DefaultValue("30") int requestsPerMinutePerIp,
        @DefaultValue("30") int autoResetMinutes,
        @DefaultValue("60") int resetCooldownSeconds,
        @DefaultValue Load load,
        @DefaultValue Sse sse) {

    public record Load(
            @DefaultValue("20") int maxVirtualUsers,
            @DefaultValue("30") int maxDurationSeconds,
            @DefaultValue("5000") int maxTotalRequests,
            @DefaultValue("10") int cooldownSeconds,
            @DefaultValue("0.01") BigDecimal minAmount,
            @DefaultValue("100.00") BigDecimal maxAmount) {
    }

    public record Sse(
            @DefaultValue("3") int maxPerIp,
            @DefaultValue("50") int maxTotal,
            @DefaultValue("5") int idleTimeoutMinutes) {
    }
}
