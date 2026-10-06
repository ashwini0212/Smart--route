package com.smartroute.routing;

import com.smartroute.common.ratelimit.RateLimitExceededException;
import com.smartroute.common.ratelimit.TokenBucketRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Caps how often one user may ask for a multi-stop optimization.
 *
 * <p>Unlike a route request, this one is expensive by design: the exact algorithm costs O(n²·2ⁿ), so 20
 * stops is roughly 1,600 times the work of 10. A bucket of 20 per minute per user keeps a dashboard
 * usable while making it impossible for one client to occupy the CPU with exact runs.
 */
@Component
public class RouteOptimizationLimiter {

    private final TokenBucketRateLimiter perUser;

    RouteOptimizationLimiter(Clock clock) {
        this.perUser = new TokenBucketRateLimiter(20, Duration.ofMinutes(1), 10_000, clock);
    }

    void check(long userId) {
        Duration wait = perUser.tryAcquire(Long.toString(userId));
        if (!wait.isZero()) {
            throw new RateLimitExceededException("Too many optimization requests; try again shortly", wait);
        }
    }

    /** Forgets all usage (tests). */
    public void reset() {
        perUser.clear();
    }
}
