package com.smartroute.algorithms.graph;

import com.smartroute.algorithms.graph.generator.CityGraphConfig;
import com.smartroute.algorithms.graph.generator.CityGraphGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AStarTest {

    private static Graph city;

    @BeforeAll
    static void buildCity() {
        city = CityGraphGenerator.generate(CityGraphConfig.grid(60, 60, 42));
    }

    enum Mode {
        DISTANCE, TRAVEL_TIME;

        EdgeWeight weight() {
            return this == DISTANCE ? EdgeWeight.DISTANCE : EdgeWeight.TRAVEL_TIME;
        }

        Heuristic heuristic(Graph graph) {
            return this == DISTANCE ? Heuristics.straightLineDistance(graph) : Heuristics.straightLineTravelTime(graph);
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void matchesDijkstraCostOnManyRandomPairs(Mode mode) {
        // The key correctness property: with a consistent heuristic A* is optimal, so its cost must equal
        // Dijkstra's for every pair (paths may differ only when several routes tie).
        Random random = new Random(7);
        Heuristic heuristic = mode.heuristic(city);
        int compared = 0;
        for (int i = 0; i < 300; i++) {
            int s = random.nextInt(city.nodeCount());
            int t = random.nextInt(city.nodeCount());
            Path dijkstra = Dijkstra.shortestPath(city, s, t, mode.weight());
            Path aStar = AStar.shortestPath(city, s, t, mode.weight(), heuristic);
            assertThat(aStar.isFound()).isEqualTo(dijkstra.isFound());
            if (dijkstra.isFound()) {
                assertThat(aStar.cost()).isCloseTo(dijkstra.cost(), within(1e-6));
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(250); // most random pairs are connected in this city
    }

    @Test
    void settlesFewerNodesThanDijkstraOnACity() {
        // Opposite corners of the grid: Dijkstra expands almost the whole city, A* heads for the target.
        int s = 0;
        int t = city.nodeCount() - 1;
        Path dijkstra = Dijkstra.shortestPath(city, s, t, EdgeWeight.DISTANCE);
        Path aStar = AStar.shortestPath(city, s, t, EdgeWeight.DISTANCE, Heuristics.straightLineDistance(city));
        assertThat(aStar.cost()).isCloseTo(dijkstra.cost(), within(1e-6));
        assertThat(aStar.nodesSettled()).isLessThan(dijkstra.nodesSettled());
    }

    @Test
    void zeroHeuristicBehavesExactlyLikeDijkstra() {
        Path dijkstra = Dijkstra.shortestPath(city, 10, 2000, EdgeWeight.TRAVEL_TIME);
        Path aStar = AStar.shortestPath(city, 10, 2000, EdgeWeight.TRAVEL_TIME, Heuristic.ZERO);
        assertThat(aStar.nodeIds()).isEqualTo(dijkstra.nodeIds());
        assertThat(aStar.nodesSettled()).isEqualTo(dijkstra.nodesSettled());
    }

    @Test
    void unreachableDestination() {
        Path path = AStar.shortestPath(TestGraphs.diamond(), 5, 0, EdgeWeight.DISTANCE, Heuristic.ZERO);
        assertThat(path.isFound()).isFalse();
    }

    @Test
    void sourceEqualsTarget() {
        Path path = AStar.shortestPath(city, 5, 5, EdgeWeight.DISTANCE, Heuristics.straightLineDistance(city));
        assertThat(path.nodeIds()).containsExactly(5);
        assertThat(path.nodesSettled()).isEqualTo(1);
    }

    @Test
    void timeHeuristicFallsBackToZeroForInfinitelyFastEdges() {
        Graph.Builder builder = Graph.builder();
        int a = builder.addNode(12.9, 77.5);
        int b = builder.addNode(12.91, 77.5);
        Graph graph = builder.addEdge(a, b, 1000, 0).build();
        assertThat(Heuristics.straightLineTravelTime(graph).estimate(a, b)).isZero();
    }

    @Test
    void distanceHeuristicNeverOverestimatesEdgeLength() {
        // Consistency check on every edge of the generated city: h(u) <= w(u,v) + h(v) for target 0.
        Heuristic h = Heuristics.straightLineDistance(city);
        int target = 0;
        for (GraphNode node : city.nodes()) {
            for (Edge edge : city.outgoing(node.id())) {
                assertThat(h.estimate(edge.from(), target))
                        .isLessThanOrEqualTo(edge.distanceMeters() + h.estimate(edge.to(), target) + 1e-6);
            }
        }
    }
}
