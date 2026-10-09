package com.settlementengine.core.lab;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The last requests the backend served (real API calls and lab actions), so a request id can be tied to audit and outbox rows. */
@LabComponent
public class LabRequestIndex implements LabResettable {

    public record Entry(String requestId, String method, String path, int status, Instant startedAt, Instant endedAt,
                        UUID userId, String actor) {
    }

    private static final int CAPACITY = 1000;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void add(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
    }

    public synchronized Optional<Entry> find(String requestId) {
        Entry found = null;
        for (Entry e : entries) {
            if (e.requestId().equals(requestId)) {
                found = e;
            }
        }
        return Optional.ofNullable(found);
    }

    public synchronized List<Entry> recent(int limit) {
        List<Entry> all = new ArrayList<>(entries);
        List<Entry> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    @Override
    public synchronized void resetInMemory() {
        entries.clear();
    }
}
