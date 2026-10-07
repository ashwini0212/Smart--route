package com.smartroute.assignment;

import com.smartroute.fleet.DriverService;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis geo index that pre-filters assignment candidates — the one Redis-backed component that had no test.
 *
 * <p>What matters here is the recovery path, not the happy one. A driver missing from the index is simply never
 * offered as a candidate: no error, no metric, nothing in the log, just a driver who mysteriously stops getting
 * work. So these tests take the index away in the two ways production takes it away — the key disappears
 * (Redis restarted empty) and a member is dropped — and assert that the next lookup rebuilds it from the
 * database, which is the source of truth.
 */
class DriverLocationIndexTest extends ApiTestSupport {

    private static final double LAT = 12.9716;
    private static final double LON = 77.5946;

    @Autowired
    private DriverLocationIndex index;

    @Autowired
    private DriverService drivers;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void aDriverIsFoundNearTheirLastReportedPosition() throws Exception {
        long driverId = locatedDriver("KA01-G-0001", LAT, LON);

        assertThat(ids(index.within(LAT, LON, 2_000))).contains(driverId);
        // Ten kilometres away, the same driver is out of range: the radius is a filter, not decoration.
        assertThat(ids(index.within(LAT + 0.09, LON, 2_000))).doesNotContain(driverId);
    }

    @Test
    void anEmptyRedisIsRebuiltFromTheDatabaseOnTheNextLookup() throws Exception {
        long driverId = locatedDriver("KA01-G-0002", LAT, LON);
        assertThat(ids(index.within(LAT, LON, 2_000))).contains(driverId);

        redis.delete(DriverLocationIndex.KEY);
        assertThat(redis.hasKey(DriverLocationIndex.KEY)).isFalse();

        assertThat(ids(index.within(LAT, LON, 2_000))).contains(driverId);
        assertThat(redis.hasKey(DriverLocationIndex.KEY)).isTrue();
    }

    @Test
    void aDriverDroppedFromTheIndexComesBackWhenItIsRebuilt() throws Exception {
        long driverId = locatedDriver("KA01-G-0003", LAT, LON);
        index.within(LAT, LON, 2_000);

        // One member gone, the key still there: the state a half-finished rebuild used to be able to leave.
        Long removed = redis.opsForZSet().remove(DriverLocationIndex.KEY, Long.toString(driverId));
        assertThat(removed).isEqualTo(1);
        assertThat(redis.opsForZSet().size(DriverLocationIndex.KEY)).isZero();

        index.rebuild();

        assertThat(redis.opsForZSet().size(DriverLocationIndex.KEY)).isEqualTo(1);
        assertThat(ids(index.within(LAT, LON, 2_000))).contains(driverId);
    }

    private long locatedDriver(String plate, double latitude, double longitude) throws Exception {
        long warehouseId = createWarehouse("WH-GEO-" + plate.substring(plate.length() - 4));
        long driverId = createDriver(warehouseId, createVehicle(plate, "VAN"));
        drivers.updateLocation(driverId, latitude, longitude, Instant.now());
        index.rebuild();
        return driverId;
    }

    private List<Long> ids(List<DriverLocationIndex.Nearby> nearby) {
        return nearby.stream().map(DriverLocationIndex.Nearby::driverId).toList();
    }
}
