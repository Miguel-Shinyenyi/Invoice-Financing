package com.settlementengine.core.lab;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Per-key token bucket: {@code capacity} tokens refilled evenly across {@code window}. */
public class TokenBucketRateLimiter {

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final int capacity;
    private final double nanosPerToken;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long idleEvictionNanos;
    private long lastSweepNanos;

    public TokenBucketRateLimiter(int capacity, Duration window, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.nanosPerToken = (double) window.toNanos() / capacity;
        this.nanoClock = nanoClock;
        this.idleEvictionNanos = window.toNanos() * 2;
        this.lastSweepNanos = nanoClock.getAsLong();
    }

    public TokenBucketRateLimiter(int capacity, Duration window) {
        this(capacity, window, System::nanoTime);
    }

    public boolean tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        sweepIfDue(now);
        Bucket bucket = buckets.computeIfAbsent(key, k -> {
            Bucket b = new Bucket();
            b.tokens = capacity;
            b.lastNanos = now;
            return b;
        });
        synchronized (bucket) {
            double refill = (now - bucket.lastNanos) / nanosPerToken;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public int trackedKeys() {
        return buckets.size();
    }

    private synchronized void sweepIfDue(long now) {
        if (now - lastSweepNanos < idleEvictionNanos / 2) {
            return;
        }
        lastSweepNanos = now;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Bucket b = it.next().getValue();
            synchronized (b) {
                if (now - b.lastNanos > idleEvictionNanos) {
                    it.remove();
                }
            }
        }
    }
}
