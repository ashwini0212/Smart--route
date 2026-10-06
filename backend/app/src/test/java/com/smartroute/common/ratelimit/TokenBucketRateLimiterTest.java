package com.smartroute.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ManualClock clock = new ManualClock();

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, Duration.ofMinutes(1), 100, clock);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("ip-1")).isZero();
        }
        Duration wait = limiter.tryAcquire("ip-1");
        // 5 tokens per minute = one every 12 s.
        assertThat(wait).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void refillsContinuouslyOverTime() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, Duration.ofMinutes(1), 100, clock);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ip-1");
        }
        clock.advance(Duration.ofSeconds(11));
        assertThat(limiter.tryAcquire("ip-1")).isEqualTo(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("ip-1")).isZero();
        assertThat(limiter.tryAcquire("ip-1")).isPositive();
    }

    @Test
    void neverRefillsBeyondCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, Duration.ofMinutes(1), 100, clock);
        clock.advance(Duration.ofHours(5));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k")).isZero();
        }
        assertThat(limiter.tryAcquire("k")).isPositive();
    }

    @Test
    void keysAreIndependent() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, Duration.ofMinutes(1), 100, clock);
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isPositive();
        assertThat(limiter.tryAcquire("b")).isZero();
    }

    @Test
    void evictsFullyRefilledBucketsWhenTooManyKeys() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, Duration.ofMinutes(1), 3, clock);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        clock.advance(Duration.ofMinutes(2));
        limiter.tryAcquire("d");
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void doesNotEvictBucketsThatStillCarryState() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, Duration.ofMinutes(1), 2, clock);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        // "a" and "b" are still empty, so evicting them would hand out free attempts.
        assertThat(limiter.tryAcquire("a")).isPositive();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, Duration.ofMinutes(1), 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        assertThat(new RateLimitExceededException("x", Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitExceededException("x", Duration.ofMillis(12_001)).retryAfterSeconds()).isEqualTo(13);
    }
}
