package com.smartroute.auth;

import com.smartroute.common.ratelimit.RateLimitExceededException;
import com.smartroute.common.ratelimit.TokenBucketRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Slows down password guessing: limits login attempts per client IP (one attacker, many accounts) and per
 * email (many IPs, one account). Both limits apply; every attempt counts, successful or not.
 */
@Component
public class LoginRateLimiter {

    private final TokenBucketRateLimiter perIp;
    private final TokenBucketRateLimiter perEmail;

    LoginRateLimiter(SecurityProperties properties, Clock clock) {
        SecurityProperties.LoginRateLimit limit = properties.loginRateLimit();
        this.perIp = new TokenBucketRateLimiter(limit.perIp(), limit.period(), limit.maxTrackedKeys(), clock);
        this.perEmail = new TokenBucketRateLimiter(limit.perEmail(), limit.period(), limit.maxTrackedKeys(), clock);
    }

    void check(String clientIp, String email) {
        Duration ipWait = perIp.tryAcquire(clientIp);
        Duration emailWait = perEmail.tryAcquire(email);
        Duration wait = ipWait.compareTo(emailWait) >= 0 ? ipWait : emailWait;
        if (!wait.isZero()) {
            throw new RateLimitExceededException("Too many login attempts; try again later", wait);
        }
    }

    /** Forgets all attempts (tests). */
    public void reset() {
        perIp.clear();
        perEmail.clear();
    }
}
