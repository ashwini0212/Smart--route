package com.smartroute.routing;

import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The multi-stop optimize endpoint on the synthetic city. */
class RouteOptimizeApiTest extends ApiTestSupport {

    private static String stop(String label, double lat, double lon) {
        return """
                {"label":"%s","location":{"latitude":%s,"longitude":%s}}""".formatted(label, lat, lon);
    }

    private String optimizeBody(String extra, String... stops) {
        return """
                {"start":{"latitude":12.9600,"longitude":77.5800},"stops":[%s]%s}"""
                .formatted(String.join(",", stops), extra);
    }

    private List<String> labelsInOrder(JsonNode route) {
        List<String> labels = new ArrayList<>();
        route.get("visits").forEach(v -> labels.add(v.get("label").asString()));
        return labels;
    }

    @Test
    void ordersStopsAlongALineInsteadOfTheRequestOrder() throws Exception {
        // Four stops in a row east of the start, handed over shuffled. Any sensible order walks the line.
        JsonNode route = body(postJson("/api/routes/optimize", optimizeBody("",
                stop("C", 12.9600, 77.6100), stop("A", 12.9600, 77.5900),
                stop("D", 12.9600, 77.6200), stop("B", 12.9600, 77.6000))).andExpect(status().isOk()));

        assertThat(labelsInOrder(route)).containsExactly("Start", "A", "B", "C", "D");
        assertThat(route.get("optimal").asBoolean()).isTrue();
        assertThat(route.get("algorithm").asString()).contains("Held-Karp");
        assertThat(route.get("stopCount").asInt()).isEqualTo(4);
        assertThat(route.get("legs")).hasSize(4);
        assertThat(route.get("lateStops")).isEmpty();
        // Each leg carries the road geometry for a map.
        assertThat(route.get("legs").get(0).get("path").size()).isGreaterThan(1);
        assertThat(route.get("totalDistanceMeters").asDouble()).isPositive();
    }

    @Test
    void theOrderIsTheCheapestOfEveryPossibleOrder() throws Exception {
        // Five stops: compare the returned total against every one of the 120 orders, computed by
        // asking the routing API for each leg. Nothing here trusts the optimizer's own numbers.
        List<double[]> points = List.of(new double[] {12.9700, 77.5900}, new double[] {12.9900, 77.6200},
                new double[] {12.9650, 77.6050}, new double[] {12.9820, 77.5850}, new double[] {12.9750, 77.6250});
        List<String> stops = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            stops.add(stop("S" + i, points.get(i)[0], points.get(i)[1]));
        }
        JsonNode route = body(postJson("/api/routes/optimize",
                optimizeBody(",\"mode\":\"SHORTEST\"", stops.toArray(String[]::new))).andExpect(status().isOk()));
        double total = route.get("totalDistanceMeters").asDouble();

        double[] start = {12.9600, 77.5800};
        List<double[]> all = new ArrayList<>();
        all.add(start);
        all.addAll(points);
        double[][] leg = new double[all.size()][all.size()];
        for (int i = 0; i < all.size(); i++) {
            for (int j = 0; j < all.size(); j++) {
                if (i != j) {
                    leg[i][j] = body(postJson("/api/routes/shortest", """
                            {"from":{"latitude":%s,"longitude":%s},"to":{"latitude":%s,"longitude":%s}}"""
                            .formatted(all.get(i)[0], all.get(i)[1], all.get(j)[0], all.get(j)[1])))
                            .get("distanceMeters").asDouble();
                }
            }
        }
        double best = Double.MAX_VALUE;
        for (List<Integer> order : permutations(List.of(1, 2, 3, 4, 5))) {
            double sum = leg[0][order.getFirst()];
            for (int k = 0; k + 1 < order.size(); k++) {
                sum += leg[order.get(k)][order.get(k + 1)];
            }
            best = Math.min(best, sum);
        }
        assertThat(total).isCloseTo(best, org.assertj.core.api.Assertions.within(1.0));
    }

    private static List<List<Integer>> permutations(List<Integer> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<Integer> rest = new ArrayList<>(items);
            Integer head = rest.remove(i);
            for (List<Integer> tail : permutations(rest)) {
                List<Integer> one = new ArrayList<>();
                one.add(head);
                one.addAll(tail);
                result.add(one);
            }
        }
        return result;
    }

    @Test
    void returnLegIsCountedWhenAsked() throws Exception {
        String stops = String.join(",", stop("A", 12.9700, 77.5900), stop("B", 12.9800, 77.6000));
        JsonNode open = body(postJson("/api/routes/optimize", optimizeBody("", stop("A", 12.9700, 77.5900),
                stop("B", 12.9800, 77.6000))).andExpect(status().isOk()));
        JsonNode closed = body(postJson("/api/routes/optimize", optimizeBody(",\"returnToStart\":true",
                stop("A", 12.9700, 77.5900), stop("B", 12.9800, 77.6000))).andExpect(status().isOk()));
        assertThat(closed.get("totalDistanceMeters").asDouble()).isGreaterThan(open.get("totalDistanceMeters").asDouble());
        assertThat(labelsInOrder(closed)).containsExactly("Start", "A", "B", "Start");
        assertThat(closed.get("legs")).hasSize(3);
        assertThat(stops).isNotEmpty();
    }

    @Test
    void theHeuristicIsNeverBetterThanTheExactAnswerAndTheGapIsReported() throws Exception {
        String[] stops = {stop("A", 12.9700, 77.5900), stop("B", 12.9900, 77.6200), stop("C", 12.9650, 77.6050),
                stop("D", 12.9820, 77.5850), stop("E", 12.9750, 77.6250), stop("F", 13.0000, 77.6000),
                stop("G", 12.9680, 77.6300)};
        JsonNode exact = body(postJson("/api/routes/optimize", optimizeBody(",\"strategy\":\"EXACT\"", stops))
                .andExpect(status().isOk()));
        JsonNode heuristic = body(postJson("/api/routes/optimize?compare=true", optimizeBody("", stops))
                .andExpect(status().isOk()));

        assertThat(exact.get("optimal").asBoolean()).isTrue();
        assertThat(heuristic.get("optimal").asBoolean()).isFalse();
        assertThat(heuristic.get("algorithm").asString()).contains("2-opt").contains("[HEURISTIC]");
        assertThat(heuristic.get("totalDurationSeconds").asDouble())
                .isGreaterThanOrEqualTo(exact.get("totalDurationSeconds").asDouble() - 0.001);
        // comparedTo is the exact total of the same matrix, so a client can show the gap honestly.
        assertThat(heuristic.get("comparedTo").asDouble()).isCloseTo(exact.get("totalDurationSeconds").asDouble(),
                org.assertj.core.api.Assertions.within(1.0));
    }

    @Test
    void stopsThatCannotBeReachedInTimeAreFlaggedNotDropped() throws Exception {
        String extra = ",\"departAt\":\"2026-10-06T10:00:00Z\"";
        String far = """
                {"label":"Late one","location":{"latitude":13.0500,"longitude":77.6800},
                 "dueBy":"2026-10-06T10:02:00Z","serviceMinutes":5}""";
        String near = """
                {"label":"In time","location":{"latitude":12.9650,"longitude":77.5850},
                 "dueBy":"2026-10-06T18:00:00Z"}""";
        JsonNode route = body(postJson("/api/routes/optimize", optimizeBody(extra, far, near))
                .andExpect(status().isOk()));

        assertThat(route.get("visits")).hasSize(3);                       // nothing was dropped
        assertThat(route.get("lateStops")).hasSize(1);
        JsonNode late = route.get("lateStops").get(0);
        assertThat(late.get("label").asString()).isEqualTo("Late one");
        assertThat(late.get("lateBySeconds").asLong()).isPositive();
        // Service time pushes later arrivals back and is reported separately.
        assertThat(route.get("serviceSeconds").asDouble()).isEqualTo(300.0);
        assertThat(route.get("finishAt").asString()).isNotBlank();
    }

    @Test
    void capacityIsRespectedRatherThanIgnored() throws Exception {
        String heavy = """
                {"label":"Pallet","location":{"latitude":12.9700,"longitude":77.5900},"weightKg":700}""";
        postJson("/api/routes/optimize", optimizeBody(",\"capacityKg\":600", heavy))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("more than the 600")));
        postJson("/api/routes/optimize", optimizeBody(",\"capacityKg\":800", heavy)).andExpect(status().isOk());
    }

    @Test
    void inputIsValidatedAndExactRefusesOversizedInput() throws Exception {
        postJson("/api/routes/optimize", optimizeBody("")).andExpect(status().isBadRequest());
        postJson("/api/routes/optimize", optimizeBody("", stop("A", 12.97, 77.59), stop("A again", 12.97, 77.59)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("repeat")));
        postJson("/api/routes/optimize", optimizeBody("", stop("Off network", 12.80, 77.40)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOCATION_OFF_NETWORK"));

        String[] many = new String[18];
        for (int i = 0; i < many.length; i++) {
            many[i] = stop("S" + i, 12.96 + i * 0.004, 77.58 + i * 0.004);
        }
        postJson("/api/routes/optimize", optimizeBody(",\"strategy\":\"EXACT\"", many))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at most 16 stops")));
        // The same input is fine for the heuristic.
        postJson("/api/routes/optimize", optimizeBody(",\"strategy\":\"HEURISTIC\"", many)).andExpect(status().isOk())
                .andExpect(jsonPath("$.optimal").value(false));
    }

    @Test
    void optimizationIsRateLimitedPerUser() throws Exception {
        String viewer = users.token(Role.VIEWER);
        String body = optimizeBody("", stop("A", 12.9700, 77.5900));
        for (int i = 0; i < 20; i++) {
            postJson("/api/routes/optimize", body, viewer).andExpect(status().isOk());
        }
        postJson("/api/routes/optimize", body, viewer).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("Retry-After"));
        // The limit is per user, so another user is unaffected.
        postJson("/api/routes/optimize", body, adminToken).andExpect(status().isOk());
    }
}
