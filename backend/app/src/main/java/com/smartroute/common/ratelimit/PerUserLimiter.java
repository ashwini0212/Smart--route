package com.smartroute.common.ratelimit;

import java.time.Clock;
import java.time.Duration;

/**
 * A per-minute cap on one endpoint, keyed by user id.
 *
 * <p>Two copies of this class existed before Phase 15 — one for the assistant, one for multi-stop
 * optimization — differing only in a capacity and a message. The shape is the same every time: a token bucket
 * per user, a bounded number of keys so the map cannot grow with traffic, and a wait that the handler turns
 * into a {@code Retry-After}. Each endpoint now declares a bean of this with its own number, which also puts
 * that number in one place instead of hidden in a constructor.
 *
 * <p>It is deliberately not the login limiter: that one keys on the email being attempted rather than on a
 * logged-in user, and it exists to slow down guessing rather than to cap cost.
 */
public class PerUserLimiter {

    /** Distinct users tracked at once; beyond this the oldest entries are evicted, not the newest refused. */
    private static final int MAX_USERS = 10_000;

    private final TokenBucketRateLimiter perUser;
    private final String message;

    public PerUserLimiter(int perMinute, String message, Clock clock) {
        this.perUser = new TokenBucketRateLimiter(perMinute, Duration.ofMinutes(1), MAX_USERS, clock);
        this.message = message;
    }

    /** Takes one token for this user, or throws with how long they must wait. */
    public void check(long userId) {
        Duration wait = perUser.tryAcquire(Long.toString(userId));
        if (!wait.isZero()) {
            throw new RateLimitExceededException(message, wait);
        }
    }

    /** Forgets all usage (tests). */
    public void reset() {
        perUser.clear();
    }
}
