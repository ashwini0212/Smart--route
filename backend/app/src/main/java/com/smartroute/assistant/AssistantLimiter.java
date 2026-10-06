package com.smartroute.assistant;

import com.smartroute.common.ratelimit.RateLimitExceededException;
import com.smartroute.common.ratelimit.TokenBucketRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Caps how often one user may ask the assistant a question.
 *
 * <p>This is the only endpoint in SmartRoute that costs money per call, and the cost is per request rather
 * than per CPU second, so the usual "it will just be slow" backstop does not apply. Ten questions a minute per
 * user is more than a person asks and far less than a loop would.
 */
@Component
public class AssistantLimiter {

    private final TokenBucketRateLimiter perUser;

    AssistantLimiter(Clock clock) {
        this.perUser = new TokenBucketRateLimiter(10, Duration.ofMinutes(1), 10_000, clock);
    }

    void check(long userId) {
        Duration wait = perUser.tryAcquire(Long.toString(userId));
        if (!wait.isZero()) {
            throw new RateLimitExceededException("Too many assistant questions; try again shortly", wait);
        }
    }

    /** Forgets all usage (tests). */
    public void reset() {
        perUser.clear();
    }
}
