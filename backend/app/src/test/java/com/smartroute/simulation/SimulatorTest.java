package com.smartroute.simulation;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.fleet.DriverService;
import com.smartroute.order.OrderService;
import com.smartroute.routing.GeoPoint;
import com.smartroute.routing.RoadNetwork;
import com.smartroute.routing.RoadNetworkProvider;
import com.smartroute.routing.RouteEngine;
import com.smartroute.support.ApiTestSupport;
import com.smartroute.tracking.LivePosition;
import com.smartroute.tracking.LivePositions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The simulators. They are off in production unless asked for, so the test builds them directly instead of
 * starting a second application context with them enabled.
 *
 * <p>What matters here is that they produce data the rest of the system can use <em>and</em> that the data is
 * labelled as invented: a position from the driver simulator must arrive as {@code SIMULATION}, and the
 * traffic simulator must only produce multipliers the real traffic API would also accept.
 */
class SimulatorTest extends ApiTestSupport {

    private static final double WH_LAT = 12.97;
    private static final double WH_LON = 77.59;

    @Autowired
    private OrderService orders;

    @Autowired
    private DriverService drivers;

    @Autowired
    private RouteEngine engine;

    @Autowired
    private RoadNetworkProvider networks;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private LivePositions positions;

    @Autowired
    private Clock clock;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long warehouseId;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-SIM");
    }

    private SimulationProperties properties(double speedKph) {
        return new SimulationProperties(true, true, Duration.ofSeconds(2), speedKph, Duration.ofSeconds(30),
                40, 4.0, 42);
    }

    private DriverMovementSimulator driverSimulator(double speedKph) {
        return new DriverMovementSimulator(orders, drivers, engine, properties(speedKph), transactions, clock);
    }

    private long driverAtTheWarehouse() throws Exception {
        long vehicleId = createVehicle("KA01-M-0001", "VAN");
        long driverId = createDriver(warehouseId, vehicleId);
        drivers.updateLocation(driverId, WH_LAT, WH_LON, Instant.now());
        putJson("/api/drivers/" + driverId + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        return driverId;
    }

    private long assignedOrder(long driverId) throws Exception {
        long orderId = body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":13.02,"dropLongitude":77.66,"priority":"NORMAL","weightKg":10,"volumeM3":0.5}
                """.formatted(warehouseId)).andExpect(status().isCreated())).get("id").asLong();
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(orderId, driverId)).andExpect(status().isCreated());
        return orderId;
    }

    private GeoPoint positionOf(long driverId) {
        LivePosition position = positions.of(driverId).orElseThrow();
        return new GeoPoint(position.latitude(), position.longitude());
    }

    @Test
    void aSimulatedDriverMovesTowardsTheDropAtRoughlyTheConfiguredSpeed() throws Exception {
        long driverId = driverAtTheWarehouse();
        assignedOrder(driverId);
        GeoPoint start = positionOf(driverId);
        // 36 km/h over a 2 s tick is 20 m; far enough to measure, short enough to stay on one street.
        DriverMovementSimulator simulator = driverSimulator(36);

        assertThat(simulator.moveOne(driverId)).isTrue();

        GeoPoint after = positionOf(driverId);
        double moved = GeoMath.haversineMeters(start.latitude(), start.longitude(), after.latitude(),
                after.longitude());
        // Along roads, so the straight-line distance covered is at most the distance driven, never more.
        assertThat(moved).isGreaterThan(5).isLessThanOrEqualTo(20.5);
    }

    @Test
    void aSimulatedPositionIsLabelledAsSimulated() throws Exception {
        long driverId = driverAtTheWarehouse();
        assignedOrder(driverId);

        driverSimulator(36).moveOne(driverId);

        // Not cosmetic: this is what stops a synthetic position being read as a real one downstream.
        assertThat(lastLocationEventPayload(driverId)).contains("SIMULATION");
    }

    /** The payload of the newest driver-location event in the outbox, which is what reaches Kafka. */
    private String lastLocationEventPayload(long driverId) {
        return jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type ="
                + " 'DRIVER_LOCATION_UPDATED' AND aggregate_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, Long.toString(driverId));
    }

    @Test
    void aDriverWithNoDeliveriesIsNotMoved() throws Exception {
        long driverId = driverAtTheWarehouse();

        assertThat(driverSimulator(36).moveOne(driverId)).isFalse();
    }

    @Test
    void movingEveryoneSkipsADriverWithoutAPosition() throws Exception {
        long positionless = createDriver(warehouseId, createVehicle("KA01-M-0002", "VAN"));
        putJson("/api/drivers/" + positionless + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        long located = driverAtTheWarehouse();
        assignedOrder(located);

        driverSimulator(36).moveEveryone();  // must not throw

        assertThat(positions.of(positionless)).isEmpty();
    }

    @Test
    void theAdvanceHelperInterpolatesInsideASegment() {
        List<double[]> path = List.of(new double[]{12.0, 77.0}, new double[]{12.001, 77.0});
        double legMeters = GeoMath.haversineMeters(12.0, 77.0, 12.001, 77.0);

        GeoPoint half = DriverMovementSimulator.advance(path, legMeters / 2);

        assertThat(half.latitude()).isBetween(12.0004, 12.0006);
        // Asking for more than the path has leaves the driver at the end of it, not past it.
        assertThat(DriverMovementSimulator.advance(path, legMeters * 10).latitude()).isEqualTo(12.001);
    }

    @Test
    void theTrafficSimulatorOnlyProducesMultipliersTheNetworkAccepts() {
        TrafficSimulator simulator = new TrafficSimulator(networks, properties(30));
        long versionBefore = networks.current().version();

        Map<RoadNetwork.EdgeKey, Double> jams = simulator.randomJams(networks.current().graph(), 25);

        assertThat(jams).isNotEmpty().hasSizeLessThanOrEqualTo(25);
        assertThat(jams.values()).allSatisfy(multiplier ->
                assertThat(multiplier).isBetween(TrafficSimulator.MIN_MULTIPLIER, 4.0));
        // Every key must be a real segment, which replaceTraffic enforces: it throws otherwise.
        RoadNetwork after = networks.replaceTraffic(jams);
        assertThat(after.version()).isEqualTo(versionBefore + 1);
        assertThat(after.traffic()).hasSameSizeAs(jams);
    }

    @Test
    void theTrafficSimulatorIsReproducibleForAGivenSeed() {
        Map<RoadNetwork.EdgeKey, Double> first =
                new TrafficSimulator(networks, properties(30)).randomJams(networks.current().graph(), 10);
        Map<RoadNetwork.EdgeKey, Double> second =
                new TrafficSimulator(networks, properties(30)).randomJams(networks.current().graph(), 10);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aJammedNetworkMakesTheSameTripSlower() {
        GeoPoint from = new GeoPoint(WH_LAT, WH_LON);
        GeoPoint to = new GeoPoint(13.02, 77.66);
        double freeFlow = engine.route(com.smartroute.routing.RouteMode.FASTEST, from, to).route().durationSeconds();
        RoadNetwork network = networks.current();
        Map<RoadNetwork.EdgeKey, Double> everything = new java.util.HashMap<>();
        for (int node = 0; node < network.graph().nodeCount(); node++) {
            if (network.graph().containsNode(node)) {
                for (Edge edge : network.graph().outgoing(node)) {
                    everything.put(new RoadNetwork.EdgeKey(edge.from(), edge.to()), 2.0);
                }
            }
        }

        networks.replaceTraffic(everything);

        assertThat(engine.route(com.smartroute.routing.RouteMode.FASTEST, from, to).route().durationSeconds())
                .isGreaterThan(freeFlow);
    }
}
