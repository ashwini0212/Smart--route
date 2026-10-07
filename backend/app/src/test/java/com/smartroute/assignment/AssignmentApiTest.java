package com.smartroute.assignment;

import com.smartroute.common.security.Role;
import com.smartroute.fleet.DriverService;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Candidate ranking, manual and automatic assignment, and the order/driver lifecycle around them. */
class AssignmentApiTest extends ApiTestSupport {

    // Warehouse from createWarehouse(): (12.97, 77.59), inside the synthetic city.
    private static final double WH_LAT = 12.97;
    private static final double WH_LON = 77.59;

    @Autowired
    private DriverService driverService;

    private long warehouseId;
    private int plates;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-ASSIGN");
    }

    private long driver(String type, double maxKg, double maxM3, double lat, double lon, boolean available) throws Exception {
        long vehicleId = body(postJson("/api/vehicles", """
                {"plateNumber":"KA01-A-%04d","type":"%s","maxWeightKg":%s,"maxVolumeM3":%s}
                """.formatted(++plates, type, maxKg, maxM3))).get("id").asLong();
        long id = createDriver(warehouseId, vehicleId);
        driverService.updateLocation(id, lat, lon, Instant.now());
        if (available) {
            putJson("/api/drivers/" + id + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        }
        return id;
    }

    private long van(double lat, double lon) throws Exception {
        return driver("VAN", 600, 4, lat, lon, true);
    }

    private long order(String priority, double kg, double m3, String requiredType) throws Exception {
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":12.99,"dropLongitude":77.62,"priority":"%s","weightKg":%s,"volumeM3":%s,
                 "requiredVehicleType":%s}
                """.formatted(warehouseId, priority, kg, m3, requiredType == null ? "null" : "\"" + requiredType + "\"")))
                .get("id").asLong();
    }

    private JsonNode candidates(long orderId, int k) throws Exception {
        return body(getUrl("/api/assignments/candidates?orderId=" + orderId + "&k=" + k).andExpect(status().isOk()));
    }

    private JsonNode assign(long orderId, long driverId) throws Exception {
        return body(postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(orderId, driverId)).andExpect(status().isCreated()));
    }

    private JsonNode driverJson(long id) throws Exception {
        return body(getUrl("/api/drivers/" + id));
    }

    private JsonNode orderJson(long id) throws Exception {
        return body(getUrl("/api/orders/" + id));
    }

    @Test
    void ranksByScoreAndExplainsExclusions() throws Exception {
        long near = van(12.972, 77.592);
        long mid = van(12.985, 77.605);
        long far = van(13.000, 77.620);
        driver("VAN", 600, 4, 12.971, 77.591, false);          // OFFLINE
        driver("BIKE", 20, 0.15, 12.971, 77.590, true);        // too small for a VAN order
        van(13.08, 77.70);                                     // ~16 km away: outside the 5 km radius
        long order = order("NORMAL", 30, 0.2, "VAN");

        JsonNode ranking = candidates(order, 5);
        assertThat(ranking.get("driversInRadius").asInt()).isEqualTo(5);
        assertThat(ranking.get("eligible").asInt()).isEqualTo(3);
        assertThat(ranking.get("excluded").get("NOT_ON_SHIFT").asInt()).isEqualTo(1);
        assertThat(ranking.get("excluded").get("VEHICLE_TOO_SMALL").asInt()).isEqualTo(1);
        List<Long> ids = new ArrayList<>();
        ranking.get("candidates").forEach(c -> ids.add(c.get("driverId").asLong()));
        assertThat(ids).containsExactly(near, mid, far);
        assertThat(ranking.get("candidates").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(ranking.get("algorithm").asString()).contains("[HEURISTIC]");

        JsonNode top2 = candidates(order, 2);
        assertThat(top2.get("candidates")).hasSize(2);
    }

    @Test
    void etaIsTheFastestRoadTimeFromDriverToWarehouse() throws Exception {
        long driverId = van(12.995, 77.615);
        long order = order("NORMAL", 5, 0.05, null);
        double eta = candidates(order, 1).get("candidates").get(0).get("etaSeconds").asDouble();

        JsonNode route = body(postJson("/api/routes/fastest", """
                {"from":{"latitude":12.995,"longitude":77.615},"to":{"latitude":%s,"longitude":%s}}
                """.formatted(WH_LAT, WH_LON)).andExpect(status().isOk()));
        assertThat(eta).isCloseTo(route.get("durationSeconds").asDouble(), within(0.01));
        assertThat(driverId).isPositive();
    }

    @Test
    void workloadSpreadsOrdersBetweenEquallyCloseDrivers() throws Exception {
        long busy = van(12.9725, 77.5925);
        long idle = van(12.9725, 77.5925);
        for (int i = 0; i < 3; i++) {
            assign(order("NORMAL", 5, 0.05, null), busy);
        }
        JsonNode ranking = candidates(order("NORMAL", 5, 0.05, null), 2);
        assertThat(ranking.get("candidates").get(0).get("driverId").asLong()).isEqualTo(idle);
        assertThat(ranking.get("candidates").get(1).get("activeDeliveries").asInt()).isEqualTo(3);
    }

    @Test
    void manualAssignmentReservesCapacityAndIsRecorded() throws Exception {
        long driverId = van(12.972, 77.592);
        long orderId = order("HIGH", 120, 0.5, null);

        JsonNode assignment = assign(orderId, driverId);
        assertThat(assignment.get("method").asString()).isEqualTo("MANUAL");
        assertThat(assignment.get("candidateRank").asInt()).isEqualTo(1);
        assertThat(assignment.get("score").asDouble()).isBetween(0.0, 1.0);

        JsonNode order = orderJson(orderId);
        assertThat(order.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(order.get("driverId").asLong()).isEqualTo(driverId);
        assertThat(order.get("assignedAt").isNull()).isFalse();
        JsonNode driver = driverJson(driverId);
        assertThat(driver.get("status").asString()).isEqualTo("ON_DELIVERY");
        assertThat(driver.get("currentLoadKg").asDouble()).isEqualTo(120.0);
        assertThat(driver.get("activeDeliveryCount").asInt()).isEqualTo(1);

        getUrl("/api/assignments?orderId=" + orderId).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].driverId").value(driverId));
        getUrl("/api/orders/" + orderId + "/history").andExpect(jsonPath("$[1].toStatus").value("ASSIGNED"));
        getUrl("/api/orders?driverId=" + driverId).andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void manualAssignmentEnforcesEveryHardRule() throws Exception {
        long bike = driver("BIKE", 20, 0.15, 12.972, 77.592, true);
        long offline = driver("VAN", 600, 4, 12.972, 77.592, false);
        long van = van(12.972, 77.592);

        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(order("NORMAL", 25, 0.1, null), bike))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not fit")));
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(order("NORMAL", 5, 0.05, "VAN"), bike))
                .andExpect(status().isUnprocessableContent());
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(order("NORMAL", 5, 0.05, null), offline))
                .andExpect(status().isUnprocessableContent());

        long once = order("NORMAL", 5, 0.05, null);
        assign(once, van);
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(once, van))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // A failed attempt changes nothing.
        assertThat(driverJson(bike).get("currentLoadKg").asDouble()).isZero();
        assertThat(driverJson(van).get("activeDeliveryCount").asInt()).isEqualTo(1);
    }

    @Test
    void maxActiveDeliveriesIsEnforced() throws Exception {
        putJson("/api/admin/assignment-config", """
                {"etaWeight":0.6,"workloadWeight":0.25,"capacityWeight":0.15,"etaCapSeconds":1800,
                 "searchRadiusMeters":5000,"maxCandidates":50,"maxActiveDeliveries":2}""").andExpect(status().isOk());
        long driverId = van(12.972, 77.592);
        assign(order("NORMAL", 1, 0.01, null), driverId);
        assign(order("NORMAL", 1, 0.01, null), driverId);
        long third = order("NORMAL", 1, 0.01, null);
        assertThat(candidates(third, 5).get("excluded").get("TOO_MANY_DELIVERIES").asInt()).isEqualTo(1);
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(third, driverId)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void unassignAndCancelReleaseTheDriver() throws Exception {
        long driverId = van(12.972, 77.592);
        long a = order("NORMAL", 100, 1, null);
        long b = order("NORMAL", 50, 0.5, null);
        assign(a, driverId);
        assign(b, driverId);
        assertThat(driverJson(driverId).get("currentLoadKg").asDouble()).isEqualTo(150.0);

        postJson("/api/orders/" + a + "/unassign", "{\"reason\":\"Customer asked to delay\"}").andExpect(status().isOk());
        JsonNode order = orderJson(a);
        assertThat(order.get("status").asString()).isEqualTo("CREATED");
        assertThat(order.get("driverId").isNull()).isTrue();
        assertThat(driverJson(driverId).get("currentLoadKg").asDouble()).isEqualTo(50.0);

        postJson("/api/orders/" + b + "/cancel", "{\"reason\":\"Customer cancelled\"}").andExpect(status().isOk());
        JsonNode driver = driverJson(driverId);
        assertThat(driver.get("currentLoadKg").asDouble()).isZero();
        assertThat(driver.get("activeDeliveryCount").asInt()).isZero();
        assertThat(driver.get("status").asString()).isEqualTo("AVAILABLE");
    }

    @Test
    void driverSeesAndUpdatesOnlyTheirOwnDeliveries() throws Exception {
        long mine = van(12.972, 77.592);
        long other = van(12.973, 77.593);
        long myOrder = order("NORMAL", 10, 0.1, null);
        long otherOrder = order("NORMAL", 10, 0.1, null);
        assign(myOrder, mine);
        assign(otherOrder, other);
        String token = users.driverToken(mine);

        getUrl("/api/deliveries/mine", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(myOrder));
        // Someone else's order looks like it doesn't exist.
        putJson("/api/deliveries/" + otherOrder + "/status", "{\"status\":\"PICKED_UP\"}", token)
                .andExpect(status().isNotFound());
        // Only road statuses can be reported.
        putJson("/api/deliveries/" + myOrder + "/status", "{\"status\":\"CANCELLED\"}", token)
                .andExpect(status().isUnprocessableContent());
        putJson("/api/deliveries/" + myOrder + "/status", "{\"status\":\"DELIVERED\"}", token)
                .andExpect(status().isConflict());

        for (String next : List.of("PICKED_UP", "IN_TRANSIT", "DELIVERED")) {
            putJson("/api/deliveries/" + myOrder + "/status", "{\"status\":\"%s\"}".formatted(next), token)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(next));
        }
        JsonNode driver = driverJson(mine);
        assertThat(driver.get("activeDeliveryCount").asInt()).isZero();
        assertThat(driver.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(orderJson(myOrder).get("driverId").asLong()).isEqualTo(mine); // kept as a record
        getUrl("/api/deliveries/mine", token).andExpect(jsonPath("$.length()").value(0));
        getUrl("/api/deliveries/mine", users.token(Role.VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void goingOfflineRequeuesOrdersNotYetPickedUp() throws Exception {
        long driverId = van(12.972, 77.592);
        long waiting = order("NORMAL", 10, 0.1, null);
        long onBoard = order("NORMAL", 20, 0.1, null);
        assign(waiting, driverId);
        assign(onBoard, driverId);
        putJson("/api/deliveries/" + onBoard + "/status", "{\"status\":\"PICKED_UP\"}").andExpect(status().isOk());

        putJson("/api/drivers/" + driverId + "/status?status=OFFLINE", "").andExpect(status().isOk());

        assertThat(orderJson(waiting).get("status").asString()).isEqualTo("CREATED");
        getUrl("/api/orders/" + waiting + "/history")
                .andExpect(jsonPath("$[2].reason").value(org.hamcrest.Matchers.containsString("went offline")));
        assertThat(orderJson(onBoard).get("status").asString()).isEqualTo("PICKED_UP");
        JsonNode driver = driverJson(driverId);
        assertThat(driver.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(driver.get("currentLoadKg").asDouble()).isEqualTo(20.0);

        // Back on shift with a parcel still on board: ON_DELIVERY, not idle.
        putJson("/api/drivers/" + driverId + "/status?status=AVAILABLE", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_DELIVERY"));
    }

    @Test
    void autoDispatchServesTheMostUrgentOrdersFirst() throws Exception {
        // One bike with room for exactly one 15 kg parcel; three orders compete for it.
        long bike = driver("BIKE", 20, 0.15, 12.972, 77.592, true);
        long low = order("LOW", 15, 0.05, null);
        long urgent = order("URGENT", 15, 0.05, null);
        long normal = order("NORMAL", 15, 0.05, null);
        long truckOnly = order("HIGH", 900, 5, "TRUCK");

        JsonNode result = body(postJson("/api/assignments/auto?limit=10", "").andExpect(status().isOk()));
        assertThat(result.get("waitingAtStart").asInt()).isEqualTo(4);
        assertThat(result.get("assignedCount").asInt()).isEqualTo(1);
        assertThat(result.get("notAssignedCount").asInt()).isEqualTo(3);
        assertThat(result.get("assigned").get(0).get("orderId").asLong()).isEqualTo(urgent);
        assertThat(result.get("assigned").get(0).get("driverId").asLong()).isEqualTo(bike);
        // Processing order: URGENT, HIGH, NORMAL, LOW.
        List<Long> skipped = new ArrayList<>();
        result.get("notAssigned").forEach(s -> skipped.add(s.get("orderId").asLong()));
        assertThat(skipped).containsExactly(truckOnly, normal, low);
        assertThat(result.get("notAssigned").get(0).get("reason").asString()).contains("vehicle too small");

        assertThat(orderJson(urgent).get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(orderJson(low).get("status").asString()).isEqualTo("CREATED");
        getUrl("/api/assignments?orderId=" + urgent).andExpect(jsonPath("$[0].method").value("AUTO"));
    }

    @Test
    void autoDispatchRespectsTheLimit() throws Exception {
        van(12.972, 77.592);
        for (int i = 0; i < 5; i++) {
            order("NORMAL", 1, 0.01, null);
        }
        JsonNode result = body(postJson("/api/assignments/auto?limit=2", "").andExpect(status().isOk()));
        assertThat(result.get("assignedCount").asInt()).isEqualTo(2);
        assertThat(result.get("stillWaiting").asInt()).isEqualTo(3);
    }

    @Test
    void searchRadiusLimitsWhoIsConsidered() throws Exception {
        van(12.972, 77.592);
        van(12.995, 77.615);
        putJson("/api/admin/assignment-config", """
                {"etaWeight":0.6,"workloadWeight":0.25,"capacityWeight":0.15,"etaCapSeconds":1800,
                 "searchRadiusMeters":1000,"maxCandidates":50,"maxActiveDeliveries":8}""").andExpect(status().isOk());
        JsonNode ranking = candidates(order("NORMAL", 1, 0.01, null), 5);
        assertThat(ranking.get("driversInRadius").asInt()).isEqualTo(1);
    }

    @Test
    void candidateLimitKeepsTheNearestEligibleDrivers() throws Exception {
        long nearest = van(12.9705, 77.5905);
        van(12.975, 77.595);
        van(12.980, 77.600);
        putJson("/api/admin/assignment-config", """
                {"etaWeight":0.6,"workloadWeight":0.25,"capacityWeight":0.15,"etaCapSeconds":1800,
                 "searchRadiusMeters":5000,"maxCandidates":1,"maxActiveDeliveries":8}""").andExpect(status().isOk());
        JsonNode ranking = candidates(order("NORMAL", 1, 0.01, null), 5);
        assertThat(ranking.get("excluded").get("BEYOND_CANDIDATE_LIMIT").asInt()).isEqualTo(2);
        assertThat(ranking.get("candidates").get(0).get("driverId").asLong()).isEqualTo(nearest);
    }

    @Test
    void onlyAdminsChangeTheWeights() throws Exception {
        String config = """
                {"etaWeight":1,"workloadWeight":0,"capacityWeight":0,"etaCapSeconds":1800,
                 "searchRadiusMeters":5000,"maxCandidates":50,"maxActiveDeliveries":8}""";
        String dispatcher = users.token(Role.DISPATCHER);
        putJson("/api/admin/assignment-config", config, dispatcher).andExpect(status().isForbidden());
        getUrl("/api/admin/assignment-config", users.token(Role.VIEWER)).andExpect(status().isForbidden());
        // The path lives under /api/admin/, which the URL rule gates on ADMIN. The annotation used to say
        // STAFF, so a dispatcher was refused by one rule while the other said yes; this holds them together.
        getUrl("/api/admin/assignment-config", dispatcher).andExpect(status().isForbidden());
        putJson("/api/admin/assignment-config", config).andExpect(status().isOk())
                .andExpect(jsonPath("$.etaWeight").value(1.0));
        putJson("/api/admin/assignment-config", config.replace("\"etaWeight\":1", "\"etaWeight\":0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void viewersAndDriversCannotAssign() throws Exception {
        long driverId = van(12.972, 77.592);
        long orderId = order("NORMAL", 1, 0.01, null);
        String body = "{\"orderId\":%d,\"driverId\":%d}".formatted(orderId, driverId);
        String viewer = users.token(Role.VIEWER);
        String driver = users.driverToken(driverId);
        postJson("/api/assignments", body, viewer).andExpect(status().isForbidden());
        postJson("/api/assignments", body, driver).andExpect(status().isForbidden());
        postJson("/api/assignments/auto", "", viewer).andExpect(status().isForbidden());
        getUrl("/api/assignments/candidates?orderId=" + orderId, driver).andExpect(status().isForbidden());
    }
}
