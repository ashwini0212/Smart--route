package com.smartroute.assignment;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.fleet.DriverCandidateView;
import com.smartroute.fleet.DriverLocationChangedEvent;
import com.smartroute.fleet.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Which drivers are within R metres of this pickup?", answered by a Redis GEO set (a sorted set keyed by
 * geohash) instead of scanning every driver.
 *
 * <p>The database stays the source of truth. The index is only a pre-filter: callers re-read each driver's
 * position and state from PostgreSQL. It is updated after each location change commits, rebuilt from the
 * database at startup, and rebuilt again if the key has disappeared (e.g. Redis restarted empty).
 *
 * <p>If Redis fails, the same question is answered by a database scan with the haversine distance, O(n)
 * in drivers, and Redis is skipped for {@link #BACK_OFF} (same pattern as the route cache).
 */
@Component
@Order(2)
class DriverLocationIndex implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationIndex.class);
    static final String KEY = "smartroute:drivers:geo";
    static final Duration BACK_OFF = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final DriverService drivers;
    private final Clock clock;
    private volatile Instant bypassUntil = Instant.EPOCH;
    /** Set when a location update could not be written: the index may hold a stale position. */
    private volatile boolean stale;

    DriverLocationIndex(StringRedisTemplate redis, DriverService drivers, Clock clock) {
        this.redis = redis;
        this.drivers = drivers;
        this.clock = clock;
    }

    /** A driver id and its straight-line distance to the query point. */
    record Nearby(long driverId, double distanceMeters) {
    }

    /** Drivers whose last known position is within {@code radiusMeters}, nearest first. */
    List<Nearby> within(double latitude, double longitude, double radiusMeters) {
        if (clock.instant().isAfter(bypassUntil)) {
            try {
                if (stale || !Boolean.TRUE.equals(redis.hasKey(KEY))) {
                    rebuild();
                }
                return searchRedis(latitude, longitude, radiusMeters);
            } catch (RuntimeException e) {
                bypassUntil = clock.instant().plus(BACK_OFF);
                log.warn("Driver location index unavailable, scanning the database for {}: {}", BACK_OFF, e.toString());
            }
        }
        return scanDatabase(latitude, longitude, radiusMeters);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onLocationChanged(DriverLocationChangedEvent event) {
        if (clock.instant().isBefore(bypassUntil)) {
            stale = true; // the next successful lookup rebuilds the index from the database
            return;
        }
        try {
            redis.opsForGeo().add(KEY, new Point(event.longitude(), event.latitude()), Long.toString(event.driverId()));
        } catch (RuntimeException e) {
            bypassUntil = clock.instant().plus(BACK_OFF);
            stale = true;
            log.warn("Could not index location of driver {}: {}", event.driverId(), e.toString());
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            rebuild();
        } catch (RuntimeException e) {
            bypassUntil = clock.instant().plus(BACK_OFF);
            log.warn("Driver location index not built at startup (Redis unavailable): {}", e.toString());
        }
    }

    /** Replaces the whole index with every located driver from the database. */
    void rebuild() {
        Map<String, Point> members = new HashMap<>();
        for (DriverCandidateView d : drivers.allLocated()) {
            members.put(Long.toString(d.id()), new Point(d.longitude(), d.latitude()));
        }
        redis.delete(KEY);
        if (!members.isEmpty()) {
            redis.opsForGeo().add(KEY, members);
        }
        stale = false;
        log.info("Driver location index rebuilt with {} drivers", members.size());
    }

    private List<Nearby> searchRedis(double latitude, double longitude, double radiusMeters) {
        var results = redis.opsForGeo().search(KEY, GeoReference.fromCoordinate(longitude, latitude),
                new Distance(radiusMeters, RedisGeoCommands.DistanceUnit.METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending());
        List<Nearby> nearby = new ArrayList<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                nearby.add(new Nearby(Long.parseLong(result.getContent().getName()), result.getDistance().getValue()));
            }
        }
        return nearby;
    }

    private List<Nearby> scanDatabase(double latitude, double longitude, double radiusMeters) {
        List<Nearby> nearby = new ArrayList<>();
        for (DriverCandidateView d : drivers.allLocated()) {
            double meters = GeoMath.haversineMeters(latitude, longitude, d.latitude(), d.longitude());
            if (meters <= radiusMeters) {
                nearby.add(new Nearby(d.id(), meters));
            }
        }
        nearby.sort(Comparator.comparingDouble(Nearby::distanceMeters));
        return nearby;
    }
}
