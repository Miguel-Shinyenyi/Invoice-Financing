package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** Traces, alerts, ML fault, health, correlation and the request picker. All read-only except the ML fault. */
@LabController
@RequestMapping("/lab")
public class LabObservabilityController {

    private final LabJaegerService jaeger;
    private final LabAlertsService alerts;
    private final LabMlService ml;
    private final LabHealthService health;
    private final LabCorrelateService correlate;
    private final LabRequestIndex requests;

    public LabObservabilityController(LabJaegerService jaeger, LabAlertsService alerts, LabMlService ml, LabHealthService health,
                                      LabCorrelateService correlate, LabRequestIndex requests) {
        this.jaeger = jaeger;
        this.alerts = alerts;
        this.ml = ml;
        this.health = health;
        this.correlate = correlate;
        this.requests = requests;
    }

    public record MlFaultRequest(Integer seconds) {
    }

    @GetMapping("/traces")
    public List<LabJaegerService.TraceSummary> traces(@RequestParam(required = false) String requestId,
                                                      @RequestParam(required = false) String settlementId,
                                                      @RequestParam(required = false) String invoiceId,
                                                      @RequestParam(defaultValue = "10") int limit) {
        return jaeger.search(requestId, settlementId, invoiceId, limit);
    }

    @GetMapping("/traces/{traceId}")
    public LabJaegerService.TraceView trace(@PathVariable String traceId) {
        return jaeger.trace(traceId);
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        return alerts.board();
    }

    @PostMapping("/ml/fault")
    public Map<String, Object> mlFault(@RequestBody MlFaultRequest body) {
        if (body == null || body.seconds() == null) {
            throw new LabValidationException("seconds is required");
        }
        return ml.startFault(body.seconds());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return health.health();
    }

    @GetMapping("/correlate")
    public Map<String, Object> correlate(@RequestParam String requestId) {
        return correlate.correlate(requestId);
    }

    @GetMapping("/requests")
    public List<LabRequestIndex.Entry> requests(@RequestParam(defaultValue = "50") int limit) {
        return requests.recent(Math.max(1, Math.min(200, limit)));
    }
}
