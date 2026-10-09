package com.settlementengine.core.lab;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Ring buffer of the last N structured log events (both services, merged). Bounded so a visitor
 * cannot make the sandbox's memory grow by generating logs.
 */
@LabComponent
public class LabLogBuffer implements LabResettable {

    public static final int DEFAULT_CAPACITY = 2000;

    private final int capacity;
    private final Deque<LabLogEvent> events = new ArrayDeque<>();
    private final List<Consumer<LabLogEvent>> subscribers = new CopyOnWriteArrayList<>();
    private long nextSeq = 1;

    public LabLogBuffer() {
        this(DEFAULT_CAPACITY);
    }

    LabLogBuffer(int capacity) {
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public void append(LabLogEvent event) {
        LabLogEvent stored;
        synchronized (this) {
            stored = event.withSeq(nextSeq++);
            events.addLast(stored);
            while (events.size() > capacity) {
                events.removeFirst();
            }
        }
        for (Consumer<LabLogEvent> subscriber : subscribers) {
            try {
                subscriber.accept(stored);
            } catch (RuntimeException ignored) {
                // a broken stream must never break logging
            }
        }
    }

    public synchronized int size() {
        return events.size();
    }

    /** The newest {@code limit} matching events, oldest first. */
    public synchronized List<LabLogEvent> query(LabLogFilter filter, int limit) {
        List<LabLogEvent> matches = new ArrayList<>();
        for (LabLogEvent e : events) {
            if (filter.matches(e)) {
                matches.add(e);
            }
        }
        int from = Math.max(0, matches.size() - limit);
        return new ArrayList<>(matches.subList(from, matches.size()));
    }

    /** Matching events with seq greater than {@code seq}, oldest first (for polling and resuming streams). */
    public synchronized List<LabLogEvent> after(long seq, LabLogFilter filter, int limit) {
        List<LabLogEvent> out = new ArrayList<>();
        for (LabLogEvent e : events) {
            if (e.seq() > seq && filter.matches(e)) {
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    public AutoCloseable subscribe(Consumer<LabLogEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    @Override
    public synchronized void resetInMemory() {
        events.clear();
    }
}
