package com.settlementengine.core.lab;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caps long-lived connections per client IP and in total. */
public class SseConnectionRegistry {

    public final class Slot implements AutoCloseable {
        private final String ip;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Slot(String ip) {
            this.ip = ip;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                release(ip);
            }
        }
    }

    private final int maxPerIp;
    private final int maxTotal;
    private final Map<String, Integer> perIp = new HashMap<>();
    private int total;

    public SseConnectionRegistry(int maxPerIp, int maxTotal) {
        this.maxPerIp = maxPerIp;
        this.maxTotal = maxTotal;
    }

    public synchronized Optional<Slot> tryAcquire(String ip) {
        int mine = perIp.getOrDefault(ip, 0);
        if (mine >= maxPerIp || total >= maxTotal) {
            return Optional.empty();
        }
        perIp.put(ip, mine + 1);
        total++;
        return Optional.of(new Slot(ip));
    }

    public synchronized int active() {
        return total;
    }

    private synchronized void release(String ip) {
        perIp.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
        total--;
    }
}
