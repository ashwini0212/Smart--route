package com.smartroute.assignment;

import com.smartroute.common.error.ApiException;
import com.smartroute.fleet.DriverService;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Double-booking: many dispatchers (threads) assigning at the same instant must never put a driver over
 * capacity or give one order to two drivers. Each thread runs the real service in its own transaction
 * against PostgreSQL, released together by a latch to maximise overlap.
 */
class AssignmentConcurrencyTest extends ApiTestSupport {

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private DriverService drivers;

    @Autowired
    private JdbcTemplate jdbc;

    private long warehouseId;
    private int plates;

    private long driver(String type, int maxKg) throws Exception {
        long vehicle = body(postJson("/api/vehicles", """
                {"plateNumber":"KA01-C-%04d","type":"%s","maxWeightKg":%d,"maxVolumeM3":20}
                """.formatted(++plates, type, maxKg))).get("id").asLong();
        long id = createDriver(warehouseId, vehicle);
        drivers.updateLocation(id, 12.972, 77.592, Instant.now());
        putJson("/api/drivers/" + id + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        return id;
    }

    private long order(int kg) throws Exception {
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":12.99,"dropLongitude":77.62,"priority":"NORMAL","weightKg":%d,"volumeM3":0.1}
                """.formatted(warehouseId, kg))).get("id").asLong();
    }

    /** Runs all tasks at once; returns how many succeeded. Only business-rule/state errors count as failures. */
    private int runTogether(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<Object> f : futures) {
                try {
                    f.get();
                    ok++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                }
            }
            return ok;
        } finally {
            // In a finally: a failing assertion above used to leave the pool's threads running for the rest
            // of the suite.
            pool.shutdownNow();
        }
    }

    @Test
    void driverIsNeverBookedBeyondCapacity() throws Exception {
        warehouseId = createWarehouse("WH-RACE");
        long van = driver("VAN", 600);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long orderId = order(100); // ten 100 kg orders, room for six
            tasks.add(() -> assignments.assignManually(orderId, van, "race", 1L));
        }
        int succeeded = runTogether(tasks);

        assertThat(succeeded).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT current_load_kg FROM driver WHERE id = ?", Double.class, van)).isEqualTo(600.0);
        assertThat(jdbc.queryForObject("SELECT active_delivery_count FROM driver WHERE id = ?", Integer.class, van)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE driver_id = ? AND status = 'ASSIGNED'",
                Integer.class, van)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Integer.class)).isEqualTo(6);
    }

    @Test
    void anOrderGoesToExactlyOneDriver() throws Exception {
        warehouseId = createWarehouse("WH-RACE");
        long orderId = order(10);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long driverId = driver("VAN", 600);
            tasks.add(() -> assignments.assignManually(orderId, driverId, "race", 1L));
        }
        int succeeded = runTogether(tasks);

        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(active_delivery_count) FROM driver", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentAutoDispatchRunsDoNotDoubleAssign() throws Exception {
        warehouseId = createWarehouse("WH-RACE");
        for (int i = 0; i < 3; i++) {
            driver("VAN", 600);
        }
        for (int i = 0; i < 30; i++) {
            order(50);
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> assignments.autoDispatch(30, 1L));
        }
        runTogether(tasks);

        // 3 vans × 8 active deliveries max (default) = 24 slots, 600 kg each = 12 orders of 50 kg per van.
        Integer assigned = jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE status = 'ASSIGNED'", Integer.class);
        assertThat(assigned).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT max(active_delivery_count) FROM driver", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM driver d WHERE d.active_delivery_count <>
                    (SELECT count(*) FROM delivery_order o WHERE o.driver_id = d.id AND o.status = 'ASSIGNED')""",
                Integer.class)).isZero();
    }
}
