package com.smartroute.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per key.
 *
 * <p>Each key has a bucket of up to {@code capacity} tokens that refills continuously at
 * {@code capacity / refillPeriod}. A request takes one token; with none left it is rejected and told how
 * long until the next token. That allows short bursts (up to {@code capacity}) but caps the long-run rate,
 * unlike a fixed window, which lets through 2× the limit around a window boundary.
 *
 * <p>Each attempt is O(1). Memory is bounded: when more than {@code maxKeys} keys exist, buckets that have
 * refilled completely (and so carry no state worth keeping) are dropped in one O(n) sweep.
 *
 * <p>Limitation: state lives in this JVM, so two instances would each allow the full rate. A shared
 * (Redis-backed) implementation of {@link RateLimiter} is the fix when the app runs on several nodes.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final double tokensPerMilli;
    private final long fullRefillMillis;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, Duration refillPeriod, int maxKeys, Clock clock) {
        if (capacity < 1 || refillPeriod.isNegative() || refillPeriod.isZero() || maxKeys < 1) {
            throw new IllegalArgumentException("capacity, refill period and maxKeys must be positive");
        }
        this.capacity = capacity;
        this.fullRefillMillis = refillPeriod.toMillis();
        this.tokensPerMilli = (double) capacity / fullRefillMillis;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    @Override
    public Duration tryAcquire(String key) {
        long now = clock.millis();
        if (buckets.size() >= maxKeys) {
            evictIdle(now);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            bucket.refill(now, capacity, tokensPerMilli);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return Duration.ZERO;
            }
            // The small epsilon stops floating-point noise (1000.0000001 ms) from rounding up a whole millisecond.
            long waitMillis = (long) Math.ceil((1 - bucket.tokens) / tokensPerMilli - 1e-6);
            return Duration.ofMillis(Math.max(1, waitMillis));
        }
    }

    /** Forgets all keys. Used by tests and when limits are reconfigured. */
    public void clear() {
        buckets.clear();
    }

    int trackedKeys() {
        return buckets.size();
    }

    private void evictIdle(long now) {
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastRefill >= fullRefillMillis);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;

        private Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        private void refill(long now, int capacity, double tokensPerMilli) {
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * tokensPerMilli);
            lastRefill = now;
        }
    }
}
