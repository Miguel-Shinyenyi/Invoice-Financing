package com.settlementengine.core.lab;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * External records the engine never learned the reference of (a lost response). Lets the UI show
 * "the external system holds this, we do not know its reference".
 */
@LabComponent
public class LabOrphanedExternalRecords implements LabResettable {

    public record Orphan(UUID settlementId, String externalRef, Instant at) {
    }

    private final List<Orphan> orphans = new ArrayList<>();

    public synchronized void add(UUID settlementId, String externalRef) {
        orphans.add(new Orphan(settlementId, externalRef, Instant.now()));
        if (orphans.size() > 500) {
            orphans.remove(0);
        }
    }

    public synchronized List<Orphan> all() {
        return List.copyOf(orphans);
    }

    public synchronized void clear() {
        orphans.clear();
    }

    @Override
    public void resetInMemory() {
        clear();
    }
}
