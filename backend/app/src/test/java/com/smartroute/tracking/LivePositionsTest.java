package com.smartroute.tracking;

import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.LocationSource;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The live position read model: what it stores, what it refuses, and what happens without Redis. */
class LivePositionsTest extends ApiTestSupport {

    @Autowired
    private LivePositions positions;

    @Autowired
    private DriverService drivers;

    @Autowired
    private StringRedisTemplate redis;

    private long warehouseId;
    private int plates;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-TRACK");
    }

    private long driver() throws Exception {
        return createDriver(warehouseId, createVehicle("KA01-T-%04d".formatted(++plates), "VAN"));
    }

    @Test
    void storesAPositionAndReadsItBack() throws Exception {
        long driverId = driver();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        positions.record(new LivePosition(driverId, 12.97, 77.59, at, LocationSource.SIMULATION));

        LivePosition stored = positions.of(driverId).orElseThrow();
        assertThat(stored.latitude()).isEqualTo(12.97);
        assertThat(stored.longitude()).isEqualTo(77.59);
        assertThat(stored.at()).isEqualTo(at);
        // The label survives the round trip, which is the point of having it.
        assertThat(stored.source()).isEqualTo(LocationSource.SIMULATION);
    }

    @Test
    void anOlderPositionDoesNotReplaceANewerOne() throws Exception {
        // A replay or a retry can deliver an old position after a new one; the map must not jump backwards.
        long driverId = driver();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        positions.record(new LivePosition(driverId, 12.97, 77.59, now, LocationSource.API));

        LivePosition kept = positions.record(
                new LivePosition(driverId, 13.10, 77.70, now.minusSeconds(30), LocationSource.API));

        assertThat(kept.latitude()).isEqualTo(12.97);
        assertThat(positions.of(driverId).orElseThrow().at()).isEqualTo(now);
    }

    @Test
    void aNewerPositionReplacesTheStoredOne() throws Exception {
        long driverId = driver();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        positions.record(new LivePosition(driverId, 12.97, 77.59, now.minusSeconds(10), LocationSource.API));

        positions.record(new LivePosition(driverId, 13.01, 77.62, now, LocationSource.API));

        assertThat(positions.of(driverId).orElseThrow().latitude()).isEqualTo(13.01);
    }

    @Test
    void withoutAnEntryItFallsBackToTheDatabase() throws Exception {
        long driverId = driver();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        drivers.updateLocation(driverId, 12.95, 77.60, at);
        // Whatever was cached is gone (Redis restarted empty, or the entry expired).
        redis.delete(LivePositions.KEY_PREFIX + driverId);

        LivePosition stored = positions.of(driverId).orElseThrow();

        assertThat(stored.latitude()).isEqualTo(12.95);
        assertThat(stored.at()).isEqualTo(at);
    }

    @Test
    void aDriverWithNoKnownPositionHasNone() throws Exception {
        assertThat(positions.of(driver())).isEmpty();
        assertThat(positions.of(999_999)).isEmpty();
    }

    @Test
    void rebuildFillsTheCacheFromTheDatabase() throws Exception {
        long first = driver();
        long second = driver();
        drivers.updateLocation(first, 12.95, 77.60, Instant.now());
        drivers.updateLocation(second, 12.96, 77.61, Instant.now());
        redis.delete(List.of(LivePositions.KEY_PREFIX + first, LivePositions.KEY_PREFIX + second));

        assertThat(positions.rebuild()).isEqualTo(2);
        assertThat(redis.opsForValue().get(LivePositions.KEY_PREFIX + first)).isNotNull();
    }

    @Test
    void allPositionsAreNewestFirstAndCoverDriversMissingFromTheCache() throws Exception {
        long older = driver();
        long newer = driver();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        drivers.updateLocation(older, 12.95, 77.60, now.minusSeconds(120));
        drivers.updateLocation(newer, 12.96, 77.61, now);
        redis.delete(LivePositions.KEY_PREFIX + older);

        List<LivePosition> all = positions.all();

        assertThat(all).hasSize(2);
        assertThat(all.getFirst().driverId()).isEqualTo(newer);
        assertThat(all.getLast().driverId()).isEqualTo(older);
    }
}
