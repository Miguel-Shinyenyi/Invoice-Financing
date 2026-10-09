package com.settlementengine.core.lab;

/** The ways the gateway boundary can misbehave. Maps to the three cases in reconciliation.md. */
public enum LabFault {
    NONE,
    /** Case 1: the request never reached the external system. */
    REQUEST_LOST,
    /** Case 2: the external system processed it, the response was lost. */
    RESPONSE_LOST,
    /** The external system answers with a definite FAILED outcome. */
    DECLINED,
    /** The external system answers correctly, late. */
    SLOW
}
