package com.settlementengine.core.lab;

/** In-memory lab state that a reset must clear (log buffer, orphan list, run history cache...). */
public interface LabResettable {

    void resetInMemory();
}
