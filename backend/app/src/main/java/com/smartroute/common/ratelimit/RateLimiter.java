package com.smartroute.common.ratelimit;

import java.time.Duration;

/** Limits how often something may happen per key (for example per client IP). */
public interface RateLimiter {

    /**
     * Takes one permit for {@code key}.
     *
     * @return {@link Duration#ZERO} if allowed, otherwise how long until the next permit is available
     */
    Duration tryAcquire(String key);
}
