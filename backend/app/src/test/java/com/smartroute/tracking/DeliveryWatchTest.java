package com.smartroute.tracking;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.fleet.DriverService;
import com.smartroute.routing.RoadNetwork;
import com.smartroute.routing.RoadNetworkProvider;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sweep that watches deliveries under way: who is predicted to be late, and whose route changed.
 *
 * <p>No broker here (the default test context), so the assertions read the outbox: an event in the outbox is
 * an event that will be published, and Phase 9's tests cover the rest of the path.
 */
class DeliveryWatchTest extends ApiTestSupport {

    // The warehouse createWarehouse() makes, inside the synthetic city.
    private static final double WH_LAT = 12.97;
    private static final double WH_LON = 77.59;

    @Autowired
    private DeliveryWatch watch;

    @Autowired
    private DriverService drivers;

    @Autowired
    private RoadNetworkProvider networks;

    @Autowired
    private JdbcTemplate jdbc;

    private long warehouseId;
    private int plates;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-WATCH");
    }

    private long availableDriver() throws Exception {
        long vehicleId = createVehicle("KA01-W-%04d".formatted(++plates), "VAN");
        long driverId = createDriver(warehouseId, vehicleId);
        drivers.updateLocation(driverId, WH_LAT, WH_LON, Instant.now());
        putJson("/api/drivers/" + driverId + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        return driverId;
    }

    /** An order with a drop across the city and a delivery window that ends {@code windowInSeconds} from now. */
    private long order(long windowInSeconds) throws Exception {
        String window = windowInSeconds == 0 ? "null"
                : "\"" + Instant.now().plusSeconds(windowInSeconds).toString() + "\"";
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":13.02,"dropLongitude":77.66,"priority":"NORMAL","weightKg":10,"volumeM3":0.5,
                 "windowEnd":%s}
                """.formatted(warehouseId, window)).andExpect(status().isCreated())).get("id").asLong();
    }

    private void assign(long orderId, long driverId) throws Exception {
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(orderId, driverId)).andExpect(status().isCreated());
    }

    private List<String> outboxTypes(String aggregateType, long id) {
        return jdbc.queryForList("""
                SELECT event_type FROM outbox_event WHERE aggregate_type = ? AND aggregate_id = ?
                ORDER BY created_at, id""", String.class, aggregateType, Long.toString(id));
    }

    private Map<String, Object> lastDelayPayload(long orderId) {
        return jdbc.queryForMap("""
                SELECT payload::text AS payload FROM outbox_event
                WHERE aggregate_type = 'order' AND aggregate_id = ? AND event_type = 'DELIVERY_DELAYED'
                ORDER BY created_at DESC, id DESC LIMIT 1""", Long.toString(orderId));
    }

    /** Slows every segment of the city down, which is the one reliable way to make any route late. */
    private void jamTheWholeCity(double multiplier) {
        RoadNetwork network = networks.current();
        Map<RoadNetwork.EdgeKey, Double> jams = new HashMap<>();
        for (int node = 0; node < network.graph().nodeCount(); node++) {
            if (network.graph().containsNode(node)) {
                for (Edge edge : network.graph().outgoing(node)) {
                    jams.put(new RoadNetwork.EdgeKey(edge.from(), edge.to()), multiplier);
                }
            }
        }
        networks.replaceTraffic(jams);
    }

    @Test
    void aDeliveryThatCannotMakeItsWindowRaisesOneAlert() throws Exception {
        long driverId = availableDriver();
        long orderId = order(30);  // 30 seconds to cross the city: impossible.
        assign(orderId, driverId);

        DeliveryWatch.SweepResult first = watch.sweep("test");

        assertThat(first.driversChecked()).isEqualTo(1);
        assertThat(first.delaysReported()).isEqualTo(1);
        assertThat(outboxTypes("order", orderId)).contains("DELIVERY_DELAYED");
        assertThat(lastDelayPayload(orderId).get("payload").toString())
                .contains("\"driverId\": " + driverId, "\"code\":");
        assertThat(jdbc.queryForObject("SELECT late_by_seconds FROM delivery_alert WHERE order_id = ?",
                Integer.class, orderId)).isPositive();

        // Nothing changed, so the second sweep must stay quiet: an alert per sweep is noise, not information.
        assertThat(watch.sweep("test").delaysReported()).isZero();
        assertThat(outboxTypes("order", orderId).stream().filter("DELIVERY_DELAYED"::equals).count()).isEqualTo(1);
    }

    @Test
    void aDeliveryThatCanMakeItsWindowRaisesNothing() throws Exception {
        long driverId = availableDriver();
        long orderId = order(7200);  // two hours to cross the synthetic city
        assign(orderId, driverId);

        assertThat(watch.sweep("test").delaysReported()).isZero();
        assertThat(outboxTypes("order", orderId)).doesNotContain("DELIVERY_DELAYED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_alert", Integer.class)).isZero();
    }

    @Test
    void anOrderWithNoWindowCannotBeLate() throws Exception {
        long driverId = availableDriver();
        long orderId = order(0);
        assign(orderId, driverId);

        assertThat(watch.sweep("test").delaysReported()).isZero();
        assertThat(outboxTypes("order", orderId)).doesNotContain("DELIVERY_DELAYED");
    }

    @Test
    void aDelayThatGetsMuchWorseIsReportedAgain() throws Exception {
        long driverId = availableDriver();
        long orderId = order(30);
        assign(orderId, driverId);
        watch.sweep("test");
        int firstLateness = jdbc.queryForObject("SELECT late_by_seconds FROM delivery_alert WHERE order_id = ?",
                Integer.class, orderId);

        // Traffic everywhere at 8x: the same trip now takes hours longer, far past the growth threshold.
        // The traffic change itself triggers the re-check (FR-22), so no sweep is called here.
        jamTheWholeCity(8.0);

        assertThat(jdbc.queryForObject("SELECT late_by_seconds FROM delivery_alert WHERE order_id = ?",
                Integer.class, orderId)).isGreaterThan(firstLateness);
        assertThat(outboxTypes("order", orderId).stream().filter("DELIVERY_DELAYED"::equals).count()).isEqualTo(2);
    }

    @Test
    void completingADeliveryClearsItsAlert() throws Exception {
        long driverId = availableDriver();
        long orderId = order(30);
        assign(orderId, driverId);
        watch.sweep("test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_alert", Integer.class)).isEqualTo(1);

        String driverToken = users.driverToken(driverId);
        for (String next : List.of("PICKED_UP", "IN_TRANSIT", "DELIVERED")) {
            putJson("/api/deliveries/" + orderId + "/status", "{\"status\":\"%s\"}".formatted(next), driverToken)
                    .andExpect(status().isOk());
        }

        // Otherwise the row would outlive the delivery: the sweep only looks at orders a driver still holds.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_alert", Integer.class)).isZero();
    }

    @Test
    void theFirstSweepStoresARouteWithoutCallingItARecalculation() throws Exception {
        long driverId = availableDriver();
        assign(order(7200), driverId);

        assertThat(watch.sweep("test").routesRecalculated()).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver_route_snapshot WHERE driver_id = ?",
                Integer.class, driverId)).isEqualTo(1);
        assertThat(outboxTypes("driver", driverId)).doesNotContain("ROUTE_RECALCULATED");
    }

    @Test
    void traffficThatChangesADriversRouteDurationIsReportedAsARecalculation() throws Exception {
        long driverId = availableDriver();
        assign(order(7200), driverId);
        watch.sweep("test");
        double before = jdbc.queryForObject("SELECT duration_seconds FROM driver_route_snapshot WHERE driver_id = ?",
                Double.class, driverId);

        // FR-22: changing traffic is enough; nothing calls the sweep here.
        jamTheWholeCity(3.0);

        assertThat(outboxTypes("driver", driverId)).contains("ROUTE_RECALCULATED");
        assertThat(jdbc.queryForObject("SELECT duration_seconds FROM driver_route_snapshot WHERE driver_id = ?",
                Double.class, driverId)).isGreaterThan(before);
        // The reason is in the event, so a reader knows why the route changed.
        assertThat(jdbc.queryForObject("""
                SELECT payload::text FROM outbox_event WHERE event_type = 'ROUTE_RECALCULATED'
                ORDER BY created_at DESC LIMIT 1""", String.class)).contains("traffic changed");
    }

    @Test
    void aTrafficChangeAloneReportsADeliveryThatCanNoLongerMakeItsWindow() throws Exception {
        long driverId = availableDriver();
        long orderId = order(1800);  // half an hour: comfortable at free-flow speed
        assign(orderId, driverId);
        watch.sweep("test");
        assertThat(outboxTypes("order", orderId)).doesNotContain("DELIVERY_DELAYED");

        jamTheWholeCity(9.0);

        // Nothing was swept by hand: the traffic change itself made the system re-check (FR-22).
        assertThat(outboxTypes("order", orderId)).contains("DELIVERY_DELAYED");
        assertThat(outboxTypes("driver", driverId)).contains("ROUTE_RECALCULATED");
    }

    @Test
    void aSweepWithNothingUnderWayDoesNothing() {
        DeliveryWatch.SweepResult result = watch.sweep("test");

        assertThat(result.driversChecked()).isZero();
        assertThat(result.delaysReported()).isZero();
        assertThat(result.routesRecalculated()).isZero();
        assertThat(result.failures()).isZero();
    }

    @Test
    void aDriverWithNoPositionIsSkippedWithoutStoppingTheSweep() throws Exception {
        // No location recorded for this one, so routing from "where the driver is" is impossible.
        long positionless = createDriver(warehouseId, createVehicle("KA01-W-9999", "VAN"));
        putJson("/api/drivers/" + positionless + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        long late = availableDriver();
        assign(order(30), positionless);
        assign(order(30), late);

        DeliveryWatch.SweepResult result = watch.sweep("test");

        assertThat(result.driversChecked()).isEqualTo(2);
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.delaysReported()).isEqualTo(1);
    }

    @Test
    void anAdministratorCanRunTheSweepThroughTheApi() throws Exception {
        long driverId = availableDriver();
        assign(order(30), driverId);

        postJson("/api/tracking/sweep", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driversChecked").value(1))
                .andExpect(jsonPath("$.delaysReported").value(1));
    }
}
