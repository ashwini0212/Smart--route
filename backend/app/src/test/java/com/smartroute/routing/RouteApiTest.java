package com.smartroute.routing;

import com.smartroute.algorithms.graph.Dijkstra;
import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.Path;
import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing API on the synthetic city (10,000-node grid around central Bengaluru), with real Redis. */
class RouteApiTest extends ApiTestSupport {

    private static final String TRIP = """
            {"from":{"latitude":12.9700,"longitude":77.5900},"to":{"latitude":13.0500,"longitude":77.6800}}""";

    @Autowired
    private RoadNetworkProvider networks;

    @Autowired
    private RouteEngine engine;

    @Test
    void shortestRouteIsOptimalAndCarriesWhatAMapNeeds() throws Exception {
        JsonNode route = body(postJson("/api/routes/shortest", TRIP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SHORTEST"))
                .andExpect(jsonPath("$.algorithm").value("A*"))
                .andExpect(jsonPath("$.optimal").value(true))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.id").isNumber()));
        int fromNode = route.get("from").get("nodeId").asInt();
        int toNode = route.get("to").get("nodeId").asInt();
        Path dijkstra = Dijkstra.shortestPath(networks.current().graph(), fromNode, toNode, EdgeWeight.DISTANCE);
        assertThat(route.get("distanceMeters").asDouble()).isCloseTo(dijkstra.cost(), within(1e-6));
        assertThat(route.get("path").size()).isEqualTo(dijkstra.nodeIds().size());
        assertThat(route.get("path").get(0).get(0).asDouble()).isEqualTo(route.get("from").get("latitude").asDouble());
        // Points inside the grid are never more than half a block diagonal (~106 m) from a node.
        assertThat(route.get("from").get("snapDistanceMeters").asDouble()).isLessThan(110);
    }

    @Test
    void fastestIsNeverSlowerAndShortestNeverLonger() throws Exception {
        JsonNode shortest = body(postJson("/api/routes/shortest", TRIP));
        JsonNode fastest = body(postJson("/api/routes/fastest", TRIP).andExpect(status().isOk()));
        assertThat(fastest.get("durationSeconds").asDouble()).isLessThanOrEqualTo(shortest.get("durationSeconds").asDouble());
        assertThat(shortest.get("distanceMeters").asDouble()).isLessThanOrEqualTo(fastest.get("distanceMeters").asDouble());
    }

    @Test
    void repeatedRequestIsServedFromTheCacheWithTheSameAnswer() throws Exception {
        JsonNode first = body(postJson("/api/routes/fastest", TRIP));
        // A few metres away: snaps to the same nodes, so it shares the cache entry.
        JsonNode second = body(postJson("/api/routes/fastest", """
                {"from":{"latitude":12.97001,"longitude":77.59001},"to":{"latitude":13.05001,"longitude":77.68001}}"""));
        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("durationSeconds").asDouble()).isEqualTo(first.get("durationSeconds").asDouble());
        assertThat(second.get("id").asLong()).isNotEqualTo(first.get("id").asLong());
    }

    @Test
    void trafficChangesTheFastestRouteButNotTheDistances() throws Exception {
        JsonNode before = body(postJson("/api/routes/fastest", TRIP));
        long version = before.get("graphVersion").asLong();
        // Jam every segment of the current fastest route.
        StringBuilder segments = new StringBuilder();
        JsonNode path = before.get("path");
        List<Integer> nodes = nodesAlong(before);
        for (int i = 0; i + 1 < nodes.size(); i++) {
            segments.append(i == 0 ? "" : ",")
                    .append("{\"fromNode\":%d,\"toNode\":%d,\"multiplier\":5}".formatted(nodes.get(i), nodes.get(i + 1)));
        }
        putJson("/api/routing/traffic", "{\"segments\":[" + segments + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 1))
                .andExpect(jsonPath("$.segmentsWithTraffic").value(nodes.size() - 1));

        JsonNode after = body(postJson("/api/routes/fastest", TRIP));
        assertThat(after.get("cached").asBoolean()).as("new network version must not reuse the old cache entry").isFalse();
        assertThat(after.get("graphVersion").asLong()).isEqualTo(version + 1);
        assertThat(after.get("durationSeconds").asDouble()).isGreaterThan(before.get("durationSeconds").asDouble());
        assertThat(path.toString()).isNotEqualTo(after.get("path").toString());
        // Distance is not affected by traffic.
        JsonNode shortest = body(postJson("/api/routes/shortest", TRIP));
        assertThat(shortest.get("distanceMeters").asDouble()).isLessThanOrEqualTo(before.get("distanceMeters").asDouble());
    }

    /** Recovers node ids from the path through the engine's own snapping of each point. */
    private List<Integer> nodesAlong(JsonNode route) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode point : route.get("path")) {
            ids.add(networks.current().index().nearest(point.get(0).asDouble(), point.get(1).asDouble()).orElseThrow().nodeId());
        }
        return ids;
    }

    @Test
    void trafficUpdatesAreAdminOnlyAndValidated() throws Exception {
        putJson("/api/routing/traffic", "{\"segments\":[]}", users.token(Role.DISPATCHER)).andExpect(status().isForbidden());
        putJson("/api/routing/traffic", "{\"segments\":[{\"fromNode\":0,\"toNode\":0,\"multiplier\":2}]}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void pointOffTheNetworkGetsAClearError() throws Exception {
        postJson("/api/routes/shortest", """
                {"from":{"latitude":12.80,"longitude":77.40},"to":{"latitude":13.05,"longitude":77.68}}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOCATION_OFF_NETWORK"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Start (12.80000, 77.40000) is")));
    }

    @Test
    void invalidCoordinatesAreRejected() throws Exception {
        postJson("/api/routes/shortest", """
                {"from":{"latitude":95,"longitude":77.6},"to":{"latitude":13.0,"longitude":77.6}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from.latitude"));
        postJson("/api/routes/shortest", "{\"from\":{\"latitude\":13.0,\"longitude\":77.6}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameStartAndDestinationIsAnEmptyRoute() throws Exception {
        postJson("/api/routes/shortest", """
                {"from":{"latitude":13.0,"longitude":77.6},"to":{"latitude":13.0,"longitude":77.6}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMeters").value(0.0))
                .andExpect(jsonPath("$.path.length()").value(1));
    }

    @Test
    void alternativesAreLabelledAndBestFirst() throws Exception {
        JsonNode routes = body(postJson("/api/routes/alternatives", """
                {"from":{"latitude":12.9700,"longitude":77.5900},"to":{"latitude":13.0500,"longitude":77.6800},
                 "mode":"SHORTEST","count":3}""").andExpect(status().isOk()));
        assertThat(routes.size()).isBetween(2, 3);
        assertThat(routes.get(0).get("optimal").asBoolean()).isTrue();
        for (int i = 1; i < routes.size(); i++) {
            assertThat(routes.get(i).get("optimal").asBoolean()).isFalse();
            assertThat(routes.get(i).get("algorithm").asText()).contains("[HEURISTIC]");
            assertThat(routes.get(i).get("distanceMeters").asDouble()).isGreaterThanOrEqualTo(routes.get(0).get("distanceMeters").asDouble());
        }
        postJson("/api/routes/alternatives", """
                {"from":{"latitude":12.97,"longitude":77.59},"to":{"latitude":13.05,"longitude":77.68},"mode":"SHORTEST","count":9}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void historyIsPrivateToTheRequesterExceptForStaffAndViewers() throws Exception {
        long warehouse = createWarehouse("WH-RT");
        String driverA = users.driverToken(createDriver(warehouse, null));
        String driverB = users.driverToken(createDriver(warehouse, null));
        long id = body(postJson("/api/routes/shortest", TRIP, driverA)).get("id").asLong();

        getUrl("/api/routes/" + id, driverA).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        getUrl("/api/routes/" + id, driverB).andExpect(status().isNotFound());
        getUrl("/api/routes/" + id, users.token(Role.VIEWER)).andExpect(status().isOk());
        getUrl("/api/routes", driverA).andExpect(jsonPath("$.totalElements").value(1));
        getUrl("/api/routes", driverB).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void storedRouteMatchesWhatWasReturned() throws Exception {
        JsonNode returned = body(postJson("/api/routes/fastest", TRIP));
        JsonNode stored = body(getUrl("/api/routes/" + returned.get("id").asLong()));
        assertThat(stored.get("durationSeconds").asDouble()).isEqualTo(returned.get("durationSeconds").asDouble());
        assertThat(stored.get("path").toString()).isEqualTo(returned.get("path").toString());
        assertThat(stored.get("from").get("nodeId").asInt()).isEqualTo(returned.get("from").get("nodeId").asInt());
    }

    @Test
    void networkEndpointSaysTheGraphIsSynthetic() throws Exception {
        getUrl("/api/routing/network", users.token(Role.VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.synthetic").value(true))
                .andExpect(jsonPath("$.source").value(org.hamcrest.Matchers.containsString("synthetic 100x100")))
                .andExpect(jsonPath("$.nodes").value(org.hamcrest.Matchers.greaterThan(9_000)));
    }

    @Test
    void readersStayConsistentWhileTrafficIsSwappedConcurrently() throws Exception {
        GeoPoint from = new GeoPoint(12.97, 77.59);
        GeoPoint to = new GeoPoint(13.05, 77.68);
        var edge = networks.current().graph().outgoing(0).getFirst();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<RouteEngine.RouteOutcome>> results = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                results.add(pool.submit(() -> engine.route(RouteMode.FASTEST, from, to)));
                if (i % 10 == 0) {
                    double multiplier = 1 + (i % 30) / 10.0;
                    networks.replaceTraffic(java.util.Map.of(new RoadNetwork.EdgeKey(edge.from(), edge.to()), multiplier));
                }
            }
            for (Future<RouteEngine.RouteOutcome> result : results) {
                RouteEngine.RouteOutcome outcome = result.get();
                assertThat(outcome.route().durationSeconds()).isPositive();
                assertThat(outcome.route().graphVersion()).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
