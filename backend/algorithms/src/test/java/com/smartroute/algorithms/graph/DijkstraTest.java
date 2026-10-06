package com.smartroute.algorithms.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DijkstraTest {

    @Test
    void findsShortestDistanceRoute() {
        Path path = Dijkstra.shortestPath(TestGraphs.diamond(), 0, 3, EdgeWeight.DISTANCE);
        assertThat(path.nodeIds()).containsExactly(0, 2, 4, 3);
        assertThat(path.cost()).isEqualTo(3);
    }

    @Test
    void fastestRouteCanDifferFromShortest() {
        Path fastest = Dijkstra.shortestPath(TestGraphs.diamond(), 0, 3, EdgeWeight.TRAVEL_TIME);
        assertThat(fastest.nodeIds()).containsExactly(0, 1, 3);
        assertThat(fastest.cost()).isEqualTo(3);
        assertThat(fastest.totalDistanceMeters()).isEqualTo(5);
    }

    @Test
    void sourceEqualsTarget() {
        Path path = Dijkstra.shortestPath(TestGraphs.diamond(), 4, 4, EdgeWeight.DISTANCE);
        assertThat(path.nodeIds()).containsExactly(4);
        assertThat(path.cost()).isZero();
    }

    @Test
    void unreachableDestination() {
        Path path = Dijkstra.shortestPath(TestGraphs.diamond(), 5, 0, EdgeWeight.DISTANCE);
        assertThat(path.isFound()).isFalse();
        assertThat(path.cost()).isInfinite();
        assertThat(path.nodeIds()).isEmpty();
    }

    @Test
    void disconnectedGraph() {
        Graph graph = TestGraphs.nodes(4).addBidirectionalEdge(0, 1, 1, 1).addBidirectionalEdge(2, 3, 1, 1).build();
        assertThat(Dijkstra.shortestPath(graph, 0, 3, EdgeWeight.DISTANCE).isFound()).isFalse();
        assertThat(Dijkstra.shortestPath(graph, 2, 3, EdgeWeight.DISTANCE).isFound()).isTrue();
    }

    @Test
    void picksCheaperOfParallelEdges() {
        Graph graph = TestGraphs.nodes(2).addEdge(0, 1, 10, 1).addEdge(0, 1, 4, 9).build();
        Path byDistance = Dijkstra.shortestPath(graph, 0, 1, EdgeWeight.DISTANCE);
        Path byTime = Dijkstra.shortestPath(graph, 0, 1, EdgeWeight.TRAVEL_TIME);
        assertThat(byDistance.cost()).isEqualTo(4);
        assertThat(byDistance.edges().getFirst().distanceMeters()).isEqualTo(4);
        assertThat(byTime.cost()).isEqualTo(1);
    }

    @Test
    void handlesCyclesAndSelfLoops() {
        Graph graph = TestGraphs.nodes(3)
                .addEdge(0, 0, 1, 1)
                .addBidirectionalEdge(0, 1, 2, 2)
                .addBidirectionalEdge(1, 2, 2, 2)
                .addEdge(2, 0, 10, 10)
                .build();
        assertThat(Dijkstra.shortestPath(graph, 0, 2, EdgeWeight.DISTANCE).cost()).isEqualTo(4);
    }

    @Test
    void zeroWeightEdgesAreAllowed() {
        Graph graph = TestGraphs.nodes(3).addEdge(0, 1, 0, 0).addEdge(1, 2, 0, 0).build();
        assertThat(Dijkstra.shortestPath(graph, 0, 2, EdgeWeight.DISTANCE).cost()).isZero();
    }

    @Test
    void equalCostRoutesAreResolvedDeterministically() {
        // Two routes of cost 2: 0-1-3 and 0-2-3. Whatever is chosen must be the same every time.
        Graph graph = TestGraphs.nodes(4)
                .addEdge(0, 1, 1, 1).addEdge(1, 3, 1, 1)
                .addEdge(0, 2, 1, 1).addEdge(2, 3, 1, 1)
                .build();
        List<Integer> first = Dijkstra.shortestPath(graph, 0, 3, EdgeWeight.DISTANCE).nodeIds();
        for (int i = 0; i < 20; i++) {
            assertThat(Dijkstra.shortestPath(graph, 0, 3, EdgeWeight.DISTANCE).nodeIds()).isEqualTo(first);
        }
        assertThat(first).containsExactly(0, 1, 3); // lower node id is settled first on ties
    }

    @Test
    void shortestPathTreeGivesCostsToAllReachableNodes() {
        ShortestPathTree tree = Dijkstra.shortestPathTree(TestGraphs.diamond(), 0, EdgeWeight.DISTANCE);
        assertThat(tree.costTo(3)).isEqualTo(3);
        assertThat(tree.costTo(5)).isEqualTo(5);
        assertThat(tree.pathTo(5).nodeIds()).containsExactly(0, 2, 4, 5);
        assertThat(tree.settledCount()).isEqualTo(6);
    }

    @Test
    void oneToManyStopsOnceAllTargetsAreSettled() {
        ShortestPathTree tree = Dijkstra.shortestPathTree(TestGraphs.diamond(), 0, EdgeWeight.DISTANCE, List.of(2, 4));
        assertThat(tree.costTo(2)).isEqualTo(1);
        assertThat(tree.costTo(4)).isEqualTo(2);
        assertThat(tree.isReachable(5)).isFalse(); // never settled: search stopped early
        assertThat(tree.settledCount()).isEqualTo(3);
    }

    @Test
    void oneToManyWithNoTargetsSettlesOnlyTheSource() {
        ShortestPathTree tree = Dijkstra.shortestPathTree(TestGraphs.diamond(), 0, EdgeWeight.DISTANCE, List.of());
        assertThat(tree.settledCount()).isEqualTo(1);
    }

    @Test
    void reversedGraphGivesCostsTowardTheSource() {
        // Distance from every node TO node 3, computed with one run on the reversed graph.
        Graph graph = TestGraphs.diamond();
        ShortestPathTree toThree = Dijkstra.shortestPathTree(graph.reversed(), 3, EdgeWeight.DISTANCE);
        for (int v : List.of(0, 1, 2, 4)) {
            assertThat(toThree.costTo(v)).isEqualTo(Dijkstra.shortestPath(graph, v, 3, EdgeWeight.DISTANCE).cost());
        }
    }

    @Test
    void reportsBothDistanceAndTimeOfTheChosenRoute() {
        Path path = Dijkstra.shortestPath(TestGraphs.diamond(), 0, 3, EdgeWeight.DISTANCE);
        assertThat(path.totalDistanceMeters()).isEqualTo(3);
        assertThat(path.totalTravelTimeSeconds()).isCloseTo(15, within(1e-9));
    }

    @Test
    void rejectsNegativeWeightsFromCustomWeightFunction() {
        assertThatThrownBy(() -> Dijkstra.shortestPath(TestGraphs.diamond(), 0, 3, edge -> -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownNodes() {
        assertThatThrownBy(() -> Dijkstra.shortestPath(TestGraphs.diamond(), 0, 6, EdgeWeight.DISTANCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dijkstra.shortestPath(Graph.builder().build(), 0, 0, EdgeWeight.DISTANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
