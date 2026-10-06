package com.smartroute.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cache-aside for computed routes in Redis.
 *
 * <p>Keys: {@code route:<network fingerprint>:<mode>:<fromNode>:<toNode>[:alt<k>]}. Snapped node ids, not raw
 * coordinates, so two requests a few metres apart share an entry. The network fingerprint changes when
 * traffic changes, so stale routes are never read; they simply expire (TTL) instead of being deleted.
 *
 * <p>Redis is an optimisation, not a dependency: every Redis error is logged (at most once a minute),
 * counted, and treated as a miss. The route is then computed as if there were no cache.
 *
 * <p>After an error the cache is bypassed for {@link #BACK_OFF} (a minimal circuit breaker). Without it,
 * every request would wait for the connection timeout twice (read and write) while Redis is down:
 * measured at ~0.5 s per route request before this was added.
 */
@Component
class RouteCache {

    private static final Logger log = LoggerFactory.getLogger(RouteCache.class);
    private static final Duration LOG_EVERY = Duration.ofMinutes(1);
    static final Duration BACK_OFF = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Clock clock;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private final Counter bypassed;
    private final AtomicReference<Instant> lastErrorLog = new AtomicReference<>(Instant.EPOCH);
    private volatile Instant bypassUntil = Instant.EPOCH;

    RouteCache(StringRedisTemplate redis, ObjectMapper objectMapper, RoutingProperties properties, Clock clock,
               MeterRegistry meters) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = properties.cacheTtl();
        this.clock = clock;
        this.hits = Counter.builder("smartroute.route.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("smartroute.route.cache").tag("result", "miss").register(meters);
        this.errors = Counter.builder("smartroute.route.cache").tag("result", "error").register(meters);
        this.bypassed = Counter.builder("smartroute.route.cache").tag("result", "bypassed").register(meters);
    }

    static String key(RoadNetwork network, RouteMode mode, int fromNode, int toNode, String suffix) {
        return "route:" + network.fingerprint() + ":" + mode + ":" + fromNode + ":" + toNode + suffix;
    }

    Optional<List<RoutePath>> get(String key) {
        if (bypassing()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(objectMapper.readValue(json, new TypeReference<List<RoutePath>>() { }));
        } catch (RuntimeException e) {
            failed("read", e);
            return Optional.empty();
        }
    }

    void put(String key, List<RoutePath> routes) {
        if (bypassing()) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(routes), ttl);
        } catch (RuntimeException e) {
            failed("write", e);
        }
    }

    private boolean bypassing() {
        if (clock.instant().isBefore(bypassUntil)) {
            bypassed.increment();
            return true;
        }
        return false;
    }

    private void failed(String operation, RuntimeException e) {
        errors.increment();
        Instant now = clock.instant();
        bypassUntil = now.plus(BACK_OFF);
        Instant last = lastErrorLog.get();
        if (now.isAfter(last.plus(LOG_EVERY)) && lastErrorLog.compareAndSet(last, now)) {
            log.warn("Route cache {} failed; computing without cache for the next {} s: {}",
                    operation, BACK_OFF.toSeconds(), e.toString());
        }
    }
}
