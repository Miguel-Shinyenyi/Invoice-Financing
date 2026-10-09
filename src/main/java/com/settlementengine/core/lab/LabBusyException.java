package com.settlementengine.core.lab;

/** The lab refuses to start the action right now (a run is active, cooling down, reset too soon). Maps to HTTP 429. */
public class LabBusyException extends RuntimeException {

    public LabBusyException(String message) {
        super(message);
    }
}
