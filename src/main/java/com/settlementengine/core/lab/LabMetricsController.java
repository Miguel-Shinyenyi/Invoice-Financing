package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@LabController
@RequestMapping("/lab/metrics")
public class LabMetricsController {

    private final LabMetricsService metrics;

    public LabMetricsController(LabMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        return metrics.snapshot();
    }
}
