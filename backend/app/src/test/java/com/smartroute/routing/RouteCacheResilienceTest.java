package com.smartroute.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis is optional: when it is unreachable the cache behaves like a miss, never throws, and backs off. */
class RouteCacheResilienceTest {

    private final LettuceConnectionFactory deadRedis = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("127.0.0.1", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void close() {
        deadRedis.destroy();
    }

    @Test
    void unreachableRedisIsAMissNotAFailure() {
        deadRedis.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(deadRedis);
        RouteCache cache = new RouteCache(template, JsonMapper.builder().build(),
                new RoutingProperties(null, 10, 10, 1, 300, Duration.ofMinutes(1)), Clock.systemUTC(), meters);

        assertThat(cache.get("route:x")).isEmpty();
        // The failure opens the breaker: the next calls skip Redis instead of waiting for it again.
        long start = System.nanoTime();
        cache.put("route:x", List.of());
        assertThat(cache.get("route:y")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(50));
        assertThat(count("error")).isEqualTo(1);
        assertThat(count("bypassed")).isEqualTo(2);
    }

    @Test
    void triesRedisAgainAfterTheBackOff() {
        deadRedis.afterPropertiesSet();
        MutableClock clock = new MutableClock();
        RouteCache cache = new RouteCache(new StringRedisTemplate(deadRedis), JsonMapper.builder().build(),
                new RoutingProperties(null, 10, 10, 1, 300, Duration.ofMinutes(1)), clock, meters);
        cache.get("a");
        clock.now = clock.now.plus(RouteCache.BACK_OFF).plusSeconds(1);
        cache.get("b");
        assertThat(count("error")).isEqualTo(2);
        assertThat(count("bypassed")).isZero();
    }

    private double count(String result) {
        return meters.get("smartroute.route.cache").tag("result", result).counter().count();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
