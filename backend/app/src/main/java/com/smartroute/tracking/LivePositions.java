package com.smartroute.tracking;

import com.smartroute.fleet.DriverCandidateView;
import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.LocationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Each driver's latest position, kept in Redis so the dashboard can ask "where is everyone?" without
 * reading the driver table.
 *
 * <p>Positions arrive from the Kafka location events, not from the write path, which is what makes this a
 * read model: it can be rebuilt by replaying the topic, and losing Redis loses nothing permanent.
 *
 * <p>Two things an event stream forces you to handle, both handled here:
 * <ul>
 *   <li><b>Out of order.</b> Kafka keeps the order of one driver's events on one partition, but a retry, a
 *       replay or a second producer can still deliver an older position after a newer one. A stored
 *       position is only replaced by one with a later timestamp.</li>
 *   <li><b>Redis being unavailable.</b> Reads fall back to the database (the source of truth) and writes
 *       are skipped, so the live map degrades to "as fresh as the last database write" instead of failing.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(TrackingProperties.class)
public class LivePositions {

    private static final Logger log = LoggerFactory.getLogger(LivePositions.class);
    static final String KEY_PREFIX = "smartroute:driver:position:";

    private final StringRedisTemplate redis;
    private final DriverService drivers;
    private final TrackingProperties properties;
    private final Clock clock;

    LivePositions(StringRedisTemplate redis, DriverService drivers, TrackingProperties properties, Clock clock) {
        this.redis = redis;
        this.drivers = drivers;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Stores a position unless a newer one is already there.
     *
     * @return the position now held for that driver, which is the incoming one unless it was out of date
     */
    public LivePosition record(LivePosition position) {
        try {
            Optional<LivePosition> stored = read(position.driverId());
            if (stored.isPresent() && stored.get().at().isAfter(position.at())) {
                log.debug("Ignored an out-of-order position for driver {} ({} is older than {})",
                        position.driverId(), position.at(), stored.get().at());
                return stored.get();
            }
            redis.opsForValue().set(KEY_PREFIX + position.driverId(), serialize(position), properties.positionTtl());
        } catch (RuntimeException redisDown) {
            log.warn("Could not store the position of driver {}: {}", position.driverId(), redisDown.toString());
        }
        return position;
    }

    /** One driver's last known position: Redis first, then the database. */
    public Optional<LivePosition> of(long driverId) {
        try {
            Optional<LivePosition> stored = read(driverId);
            if (stored.isPresent()) {
                return stored;
            }
        } catch (RuntimeException redisDown) {
            log.warn("Could not read the position of driver {}: {}", driverId, redisDown.toString());
        }
        return fromDatabase(driverId);
    }

    /** Every driver that has a known position, newest first. The database fills in whatever Redis lacks. */
    public List<LivePosition> all() {
        List<LivePosition> positions = new ArrayList<>();
        for (DriverCandidateView driver : drivers.allLocated()) {
            Optional<LivePosition> live = Optional.empty();
            try {
                live = read(driver.id());
            } catch (RuntimeException redisDown) {
                log.warn("Could not read live positions: {}", redisDown.toString());
            }
            positions.add(live.orElseGet(() -> new LivePosition(driver.id(), driver.latitude(),
                    driver.longitude(), driver.locationUpdatedAt(), LocationSource.API)));
        }
        positions.sort(Comparator.comparing(LivePosition::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return positions;
    }

    /** Rebuilds the read model from the database; used at startup and after Redis has been empty. */
    public int rebuild() {
        int written = 0;
        for (DriverCandidateView driver : drivers.allLocated()) {
            if (driver.latitude() == null) {
                continue;
            }
            LivePosition position = new LivePosition(driver.id(), driver.latitude(), driver.longitude(),
                    driver.locationUpdatedAt() == null ? clock.instant() : driver.locationUpdatedAt(),
                    LocationSource.API);
            try {
                redis.opsForValue().set(KEY_PREFIX + position.driverId(), serialize(position),
                        properties.positionTtl());
                written++;
            } catch (RuntimeException redisDown) {
                log.warn("Could not rebuild live positions: {}", redisDown.toString());
                return written;
            }
        }
        return written;
    }

    private Optional<LivePosition> read(long driverId) {
        String value = redis.opsForValue().get(KEY_PREFIX + driverId);
        return value == null ? Optional.empty() : Optional.of(deserialize(driverId, value));
    }

    private Optional<LivePosition> fromDatabase(long driverId) {
        return drivers.candidates(List.of(driverId)).stream()
                .filter(driver -> driver.latitude() != null)
                .map(driver -> new LivePosition(driver.id(), driver.latitude(), driver.longitude(),
                        driver.locationUpdatedAt(), LocationSource.API))
                .findFirst();
    }

    /**
     * Stored as "lat|lon|epochMillis|source" rather than JSON: it is read on every map refresh, and the
     * shape is fixed by this class alone.
     */
    private static String serialize(LivePosition position) {
        return position.latitude() + "|" + position.longitude() + "|" + position.at().toEpochMilli()
                + "|" + position.source().name();
    }

    private static LivePosition deserialize(long driverId, String value) {
        String[] parts = value.split("\\|");
        return new LivePosition(driverId, Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Instant.ofEpochMilli(Long.parseLong(parts[2])), LocationSource.valueOf(parts[3]));
    }

    Duration positionTtl() {
        return properties.positionTtl();
    }
}
