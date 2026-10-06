package com.smartroute.common.ratelimit;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;

import java.time.Duration;

/** 429 Too Many Requests; the handler adds a {@code Retry-After} header. */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;

    public RateLimitExceededException(String message, Duration retryAfter) {
        super(ErrorCode.RATE_LIMITED, message);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, as the Retry-After header requires. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
