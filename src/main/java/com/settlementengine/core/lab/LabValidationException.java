package com.settlementengine.core.lab;

/** A lab request parameter outside the server-side caps. Maps to HTTP 400. */
public class LabValidationException extends RuntimeException {

    public LabValidationException(String message) {
        super(message);
    }
}
