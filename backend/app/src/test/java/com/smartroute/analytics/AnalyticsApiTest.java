package com.smartroute.analytics;

import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The analytics queries, against a delivery history built row by row.
 *
 * <p>The history is written directly with SQL rather than by driving the API through a day of deliveries:
 * these numbers are about <em>when</em> things happened, and the only way to have an order delivered an hour
 * late yesterday is to say so. The scenario below is the fixture every assertion reads.
 */
class AnalyticsApiTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long warehouseId;
    private long driverOne;
    private long driverTwo;

    @BeforeEach
    void buildAHistory() throws Exception {
        warehouseId = createWarehouse("WH-AN");
        driverOne = createDriver(warehouseId, createVehicle("KA01-A-0001", "VAN"));
        driverTwo = createDriver(warehouseId, createVehicle("KA01-A-0002", "VAN"));

        Instant now = clock.instant();
        // Two deliveries by driver one: one inside its window, one an hour past it.
        long onTime = order("ORD-ON-TIME", now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(2)));
        deliver(onTime, driverOne, now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(4)),
                now.minus(Duration.ofHours(3)), 600);
        long late = order("ORD-LATE", now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(4)));
        deliver(late, driverOne, now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(4)),
                now.minus(Duration.ofHours(3)), 900);
        // One by driver two with no window at all: it can be neither on time nor late.
        long noWindow = order("ORD-NO-WINDOW", now.minus(Duration.ofHours(6)), null);
        deliver(noWindow, driverTwo, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(100)),
                now.minus(Duration.ofMinutes(30)), 1200);
        // One still waiting for a driver.
        order("ORD-WAITING", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));
    }

    /** Inserts an order in CREATED with its history row, and returns its id. */
    private long order(String code, Instant createdAt, Instant windowEnd) {
        Long id = jdbc.queryForObject("""
                INSERT INTO delivery_order (code, warehouse_id, customer_name, drop_address, drop_latitude,
                        drop_longitude, priority, status, weight_kg, volume_m3, window_end, created_at, updated_at)
                VALUES (?, ?, 'Fictional Customer', '1 Test Street', 12.98, 77.60, 'NORMAL', 'CREATED', 10, 0.5, ?, ?, ?)
                RETURNING id
                """, Long.class, code, warehouseId, windowEnd == null ? null : java.sql.Timestamp.from(windowEnd),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
        history(id, null, "CREATED", createdAt);
        return id;
    }

    /** Walks an order through assignment, pickup and delivery at the given times. */
    private void deliver(long orderId, long driverId, Instant assignedAt, Instant pickedUpAt, Instant deliveredAt,
                         double predictedEtaSeconds) {
        // The order carries its driver, as it does in the real flow: that is what a delivery is attributed to.
        jdbc.update("UPDATE delivery_order SET status = 'DELIVERED', driver_id = ?, assigned_at = ?, updated_at = ?"
                        + " WHERE id = ?",
                driverId, java.sql.Timestamp.from(assignedAt), java.sql.Timestamp.from(deliveredAt), orderId);
        jdbc.update("""
                INSERT INTO assignment (order_id, driver_id, method, eta_seconds, created_at, updated_at)
                VALUES (?, ?, 'MANUAL', ?, ?, ?)
                """, orderId, driverId, predictedEtaSeconds, java.sql.Timestamp.from(assignedAt),
                java.sql.Timestamp.from(assignedAt));
        history(orderId, "CREATED", "ASSIGNED", assignedAt);
        history(orderId, "ASSIGNED", "PICKED_UP", pickedUpAt);
        history(orderId, "PICKED_UP", "IN_TRANSIT", pickedUpAt.plusSeconds(60));
        history(orderId, "IN_TRANSIT", "DELIVERED", deliveredAt);
    }

    private void history(long orderId, String from, String to, Instant at) {
        jdbc.update("INSERT INTO order_status_history (order_id, from_status, to_status, changed_at) VALUES (?, ?, ?, ?)",
                orderId, from, to, java.sql.Timestamp.from(at));
    }

    @Test
    void theOverviewCountsWhatHappenedAndSaysWhatOnTimeMeans() throws Exception {
        getUrl("/api/analytics/overview?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(4))
                .andExpect(jsonPath("$.delivered").value(3))
                .andExpect(jsonPath("$.waitingNow").value(1))
                // Two of the three delivered orders had a window; one of those made it.
                .andExpect(jsonPath("$.deliveredWithWindow").value(2))
                .andExpect(jsonPath("$.onTime").value(1))
                .andExpect(jsonPath("$.late").value(1))
                .andExpect(jsonPath("$.onTimeRate").value(0.5))
                .andExpect(jsonPath("$.definitions").isArray())
                .andExpect(jsonPath("$.definitions[0]").isString());
    }

    @Test
    void theOverviewReportsTheSpreadOfDeliveryTimesRatherThanOneAverage() throws Exception {
        getUrl("/api/analytics/overview?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToDeliveredMinutes.samples").value(3))
                // Two deliveries took two hours from assignment, one took 90 minutes.
                .andExpect(jsonPath("$.assignedToDeliveredMinutes.p50").value(120.0))
                .andExpect(jsonPath("$.assignedToDeliveredMinutes.mean").value(110.0));
    }

    @Test
    void anOrderWithoutAWindowIsNeitherOnTimeNorLate() throws Exception {
        getUrl("/api/analytics/fleet?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perDriver[?(@.driverId == " + driverTwo + ")].delivered").value(1))
                .andExpect(jsonPath("$.perDriver[?(@.driverId == " + driverTwo + ")].late").value(0));
    }

    @Test
    void fleetUsageSeparatesTheFleetFromTheDriversWhoWorked() throws Exception {
        getUrl("/api/analytics/fleet?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverCount").value(2))
                .andExpect(jsonPath("$.driversWithDeliveries").value(2))
                .andExpect(jsonPath("$.shareOfFleetUsed").value(1.0))
                .andExpect(jsonPath("$.deliveriesPerActiveDriver").value(1.5))
                .andExpect(jsonPath("$.perDriver[?(@.driverId == " + driverOne + ")].delivered").value(2))
                .andExpect(jsonPath("$.perDriver[?(@.driverId == " + driverOne + ")].late").value(1))
                // The definition has to travel with the number: this is not a share of anyone's time.
                .andExpect(jsonPath("$.definitions[1]").value(org.hamcrest.Matchers.containsString("not a share of anyone's time")));
    }

    @Test
    void aDeliveryIsCountedOnceEvenWhenItsOrderWasAssignedSeveralTimes() throws Exception {
        // Reassignment and auto-dispatch retries leave several assignment rows on one order. Joining them to
        // the status history counted the delivery once per row, which is how the fleet page first reported a
        // driver delivering 24 orders on a day the whole fleet delivered 45.
        Long orderId = jdbc.queryForObject("SELECT id FROM delivery_order WHERE code = 'ORD-ON-TIME'", Long.class);
        Instant earlier = clock.instant().minus(Duration.ofHours(6));
        for (int extra = 0; extra < 3; extra++) {
            jdbc.update("""
                    INSERT INTO assignment (order_id, driver_id, method, eta_seconds, created_at, updated_at)
                    VALUES (?, ?, 'AUTO', 300, ?, ?)
                    """, orderId, driverOne, java.sql.Timestamp.from(earlier.plusSeconds(extra)),
                    java.sql.Timestamp.from(earlier.plusSeconds(extra)));
        }

        getUrl("/api/analytics/fleet?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perDriver[?(@.driverId == " + driverOne + ")].delivered").value(2))
                .andExpect(jsonPath("$.deliveriesPerActiveDriver").value(1.5));
        getUrl("/api/analytics/eta-accuracy?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples").value(3));
    }

    @Test
    void throughputHasOneRowPerDayIncludingTheQuietOnes() throws Exception {
        getUrl("/api/analytics/throughput?days=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.created > 0)].created").exists());
    }

    @Test
    void etaAccuracyComparesThePredictionWithWhatHappened() throws Exception {
        getUrl("/api/analytics/eta-accuracy?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples").value(3))
                // Predictions were 10, 15 and 20 minutes; pickups took 60, 60 and 20 minutes.
                .andExpect(jsonPath("$.predictedMedianMinutes").value(15.0))
                .andExpect(jsonPath("$.actualMedianMinutes").value(60.0))
                .andExpect(jsonPath("$.medianDifferenceMinutes").value(45.0))
                .andExpect(jsonPath("$.definitions[2]").value(org.hamcrest.Matchers.containsString("upper bound")));
    }

    @Test
    void anEmptyWindowReportsNothingRatherThanZeroPercent() throws Exception {
        // One hour ago nothing had been delivered yet in this fixture's last hour except the no-window order.
        jdbc.update("DELETE FROM order_status_history WHERE to_status = 'DELIVERED'");

        getUrl("/api/analytics/overview?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(0))
                .andExpect(jsonPath("$.onTimeRate").doesNotExist())
                .andExpect(jsonPath("$.assignedToDeliveredMinutes.samples").value(0))
                .andExpect(jsonPath("$.assignedToDeliveredMinutes.p50").doesNotExist());
    }

    @Test
    void analyticsAreReadableByViewersAndNotByDrivers() throws Exception {
        getUrl("/api/analytics/overview", users.token(Role.VIEWER)).andExpect(status().isOk());
        getUrl("/api/analytics/fleet", users.token(Role.DISPATCHER)).andExpect(status().isOk());
        getUrl("/api/analytics/overview", users.driverToken(driverOne)).andExpect(status().isForbidden());
        getUrl("/api/analytics/overview", null).andExpect(status().isUnauthorized());
    }

    @Test
    void theWindowIsValidated() throws Exception {
        getUrl("/api/analytics/overview?days=0").andExpect(status().isBadRequest());
        getUrl("/api/analytics/overview?days=500").andExpect(status().isBadRequest());
    }
}
