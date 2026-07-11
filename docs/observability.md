# Observability

## Purpose

Describes how the platform is monitored: metrics, logs, and distributed traces across services.

## Current state

Not yet built. Planned for Phase 6, after services are running in Kubernetes.

### Metrics

- Spring Boot Actuator exposes metrics through Micrometer
- Prometheus scrapes metrics from backend, ml-service, and Kafka consumers
- Grafana dashboards for: transfer volume, transfer latency, provider connector error rates, fraud score distribution, Kafka consumer lag

### Logs

- Structured JSON logs from every service, no plain text logs
- Logs shipped to a central store (CloudWatch Logs to start, Loki if cost becomes a concern)
- Every log line includes a request ID and, where relevant, a transfer ID, so a single transfer can be traced across services

### Traces

- OpenTelemetry instrumentation on the backend and ml-service
- Traces exported to a backend like Jaeger or AWS X-Ray
- Every cross-service call (backend to Kafka to fraud service to backend) shares one trace ID, so a slow or failed transfer can be followed end to end

### Alerts

- Alert on: Kafka consumer lag past a threshold, error rate spikes on provider connectors, fraud service downtime, unusual drop or spike in transfer volume

## Why this matters for this project

External calls (the money-movement side of a settlement) are the most likely failure point, since they depend on systems outside this platform's control, and they're exactly where `UNKNOWN` states get created. Observability here isn't optional, it's how a mismatch or a stuck `UNKNOWN` settlement actually gets noticed instead of sitting silently until reconciliation catches it hours later.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Request ID and transfer ID on every log line | Standard practice for tracing a single business transaction across a distributed system, essential for debugging provider connector failures |
| 2026-07-11 | OpenTelemetry over vendor-specific tracing SDKs | Vendor-neutral, avoids lock-in, works with Jaeger or X-Ray interchangeably |

## Open questions

- Log retention period. Decide once storage costs are relevant.
