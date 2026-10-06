package com.smartroute.algorithms.graph;

import com.smartroute.algorithms.graph.generator.CityGraphConfig;
import com.smartroute.algorithms.graph.generator.CityGraphGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AlternativeRoutesTest {

    private static Graph city;
    private static Heuristic distance;

    @BeforeAll
    static void buildCity() {
        city = LargestComponent.of(CityGraphGenerator.generate(CityGraphConfig.grid(40, 40, 42))).graph();
        distance = Heuristics.straightLineDistance(city);
    }

    @Test
    void firstRouteIsTheOptimalOne() {
        List<Path> routes = AlternativeRoutes.find(city, 0, city.nodeCount() - 1, EdgeWeight.DISTANCE, distance, 3);
        Path optimal = Dijkstra.shortestPath(city, 0, city.nodeCount() - 1, EdgeWeight.DISTANCE);
        assertThat(routes.getFirst().cost()).isCloseTo(optimal.cost(), within(1e-6));
    }

    @Test
    void alternativesAreDifferentEnoughAndNotTooLong() {
        int target = city.nodeCount() - 1;
        List<Path> routes = AlternativeRoutes.find(city, 0, target, EdgeWeight.DISTANCE, distance, 3);
        assertThat(routes).hasSizeGreaterThan(1);
        double best = routes.getFirst().cost();
        for (int i = 0; i < routes.size(); i++) {
            Path route = routes.get(i);
            assertThat(route.nodeIds().getFirst()).isZero();
            assertThat(route.nodeIds().getLast()).isEqualTo(target);
            // Reported cost is the real one, not the penalized one.
            assertThat(route.cost()).isCloseTo(route.totalDistanceMeters(), within(1e-6));
            assertThat(route.cost()).isLessThanOrEqualTo(AlternativeRoutes.DEFAULT_MAX_STRETCH * best + 1e-6);
            for (int j = 0; j < i; j++) {
                assertThat(AlternativeRoutes.overlap(route, routes.get(j), EdgeWeight.DISTANCE))
                        .isLessThanOrEqualTo(AlternativeRoutes.DEFAULT_MAX_OVERLAP);
            }
        }
    }

    @Test
    void worksInTravelTimeMode() {
        Heuristic time = Heuristics.straightLineTravelTime(city);
        List<Path> routes = AlternativeRoutes.find(city, 5, city.nodeCount() - 7, EdgeWeight.TRAVEL_TIME, time, 2);
        Path optimal = Dijkstra.shortestPath(city, 5, city.nodeCount() - 7, EdgeWeight.TRAVEL_TIME);
        assertThat(routes.getFirst().cost()).isCloseTo(optimal.cost(), within(1e-6));
    }

    @Test
    void returnsOnlyOneRouteWhenThereIsNoAlternative() {
        Graph line = TestGraphs.nodes(4).addEdge(0, 1, 1, 1).addEdge(1, 2, 1, 1).addEdge(2, 3, 1, 1).build();
        assertThat(AlternativeRoutes.find(line, 0, 3, EdgeWeight.DISTANCE, Heuristic.ZERO, 3)).hasSize(1);
    }

    @Test
    void rejectsAlternativesThatAreTooLong() {
        // Direct road of 10 vs a detour of 30: the detour exceeds the 1.4 stretch limit.
        Graph graph = TestGraphs.nodes(3).addEdge(0, 2, 10, 10).addEdge(0, 1, 15, 15).addEdge(1, 2, 15, 15).build();
        assertThat(AlternativeRoutes.find(graph, 0, 2, EdgeWeight.DISTANCE, Heuristic.ZERO, 2)).hasSize(1);
        assertThat(AlternativeRoutes.find(graph, 0, 2, EdgeWeight.DISTANCE, Heuristic.ZERO, 2, 1.4, 3.0, 0.7)).hasSize(2);
    }

    @Test
    void unreachableTargetGivesNoRoutes() {
        Graph graph = TestGraphs.nodes(2).build();
        assertThat(AlternativeRoutes.find(graph, 0, 1, EdgeWeight.DISTANCE, Heuristic.ZERO, 3)).isEmpty();
    }

    @Test
    void sameSourceAndTargetGivesOneEmptyRoute() {
        List<Path> routes = AlternativeRoutes.find(city, 3, 3, EdgeWeight.DISTANCE, distance, 3);
        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().hopCount()).isZero();
    }

    @Test
    void validatesParameters() {
        assertThatThrownBy(() -> AlternativeRoutes.find(city, 0, 1, EdgeWeight.DISTANCE, distance, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlternativeRoutes.find(city, 0, 1, EdgeWeight.DISTANCE, distance, 2, 1.0, 1.4, 0.7))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
