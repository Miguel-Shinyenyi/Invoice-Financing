package com.settlementengine.core.lab;

/** A sidecar (Jaeger, Prometheus, Alertmanager, ml-service) did not answer. Maps to 503, never a stack trace. */
public class LabUpstreamUnavailableException extends RuntimeException {

    public LabUpstreamUnavailableException(String message) {
        super(message);
    }
}
