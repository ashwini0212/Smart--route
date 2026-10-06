package com.smartroute.algorithms.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreadthFirstSearchTest {

    @Test
    void findsPathWithFewestEdgesEvenWhenLongerInDistance() {
        Path path = BreadthFirstSearch.fewestHops(TestGraphs.diamond(), 0, 3);
        assertThat(path.nodeIds()).containsExactly(0, 1, 3);
        assertThat(path.cost()).isEqualTo(2);
        assertThat(path.totalDistanceMeters()).isEqualTo(5); // longer than the 3 m Dijkstra route
    }

    @Test
    void sourceEqualsTargetIsZeroHopPath() {
        Path path = BreadthFirstSearch.fewestHops(TestGraphs.diamond(), 2, 2);
        assertThat(path.nodeIds()).containsExactly(2);
        assertThat(path.hopCount()).isZero();
    }

    @Test
    void singleNodeGraph() {
        Graph graph = TestGraphs.nodes(1).build();
        assertThat(BreadthFirstSearch.fewestHops(graph, 0, 0).isFound()).isTrue();
        assertThat(BreadthFirstSearch.withinHops(graph, 0, 3)).containsExactly(0);
    }

    @Test
    void unreachableTargetReturnsNotFound() {
        Path path = BreadthFirstSearch.fewestHops(TestGraphs.diamond(), 3, 0); // edges point away from 0
        assertThat(path.isFound()).isFalse();
        assertThat(path.cost()).isInfinite();
    }

    @Test
    void terminatesOnCycles() {
        Graph cycle = TestGraphs.nodes(3).addEdge(0, 1, 1, 1).addEdge(1, 2, 1, 1).addEdge(2, 0, 1, 1).build();
        assertThat(BreadthFirstSearch.fewestHops(cycle, 1, 0).nodeIds()).containsExactly(1, 2, 0);
    }

    @Test
    void withinHopsRespectsLimitAndReturnsBfsOrder() {
        Graph graph = TestGraphs.diamond();
        assertThat(BreadthFirstSearch.withinHops(graph, 0, 0)).containsExactly(0);
        assertThat(BreadthFirstSearch.withinHops(graph, 0, 1)).containsExactly(0, 1, 2);
        assertThat(BreadthFirstSearch.withinHops(graph, 0, 2)).containsExactly(0, 1, 2, 3, 4);
        assertThat(BreadthFirstSearch.withinHops(graph, 0, 10)).hasSize(6);
    }

    @Test
    void rejectsInvalidInput() {
        Graph graph = TestGraphs.diamond();
        assertThatThrownBy(() -> BreadthFirstSearch.withinHops(graph, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreadthFirstSearch.fewestHops(graph, 0, 99)).isInstanceOf(IllegalArgumentException.class);
    }
}
