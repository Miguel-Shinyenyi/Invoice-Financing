package com.settlementengine.core.lab;

/** What a lost request or response looks like to the caller: a plain exception from the gateway call. */
public class LabInjectedFaultException extends RuntimeException {

    public LabInjectedFaultException(LabFault fault) {
        super("Injected gateway fault: " + fault);
    }
}
