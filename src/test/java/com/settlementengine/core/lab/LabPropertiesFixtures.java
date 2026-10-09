package com.settlementengine.core.lab;

import java.math.BigDecimal;
import java.util.List;

final class LabPropertiesFixtures {

    private LabPropertiesFixtures() {
    }

    static LabProperties withReadOnly(boolean readOnly, int perMinute) {
        LabProperties d = defaults();
        return new LabProperties(true, readOnly, false, d.forbiddenHosts(), perMinute, d.autoResetMinutes(),
                d.resetCooldownSeconds(), d.load(), d.sse(), d.upstreams());
    }

    static LabProperties.Upstreams upstreams(String base) {
        return new LabProperties.Upstreams(base, base, base, base, "/nonexistent/alert-rules.yml", 30);
    }

    static LabProperties defaults() {
        return new LabProperties(true, false, false, List.of("107.155.122.29"), 30, 30, 60,
                new LabProperties.Load(20, 30, 5000, 10, new BigDecimal("0.01"), new BigDecimal("100.00")),
                new LabProperties.Sse(3, 50, 5), upstreams("http://localhost:1"));
    }
}
