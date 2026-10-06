package com.smartroute.algorithms.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphTest {

    @Test
    void emptyGraphHasNoNodesOrEdges() {
        Graph graph = Graph.builder().build();
        assertThat(graph.isEmpty()).isTrue();
        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.edgeCount()).isZero();
    }

    @Test
    void nodeIdsAreDenseAndOrdered() {
        Graph.Builder builder = Graph.builder();
        assertThat(builder.addNode(12.9, 77.5)).isZero();
        assertThat(builder.addNode(12.8, 77.6)).isEqualTo(1);
        Graph graph = builder.build();
        assertThat(graph.node(1).latitude()).isEqualTo(12.8);
    }

    @Test
    void parallelEdgesAreKept() {
        Graph graph = TestGraphs.nodes(2).addEdge(0, 1, 10, 1).addEdge(0, 1, 5, 2).build();
        assertThat(graph.outgoing(0)).hasSize(2);
        assertThat(graph.edgeCount()).isEqualTo(2);
    }

    @Test
    void bidirectionalEdgeCreatesTwoDirectedEdges() {
        Graph graph = TestGraphs.nodes(2).addBidirectionalEdge(0, 1, 10, 1).build();
        assertThat(graph.outgoing(0)).extracting(Edge::to).containsExactly(1);
        assertThat(graph.outgoing(1)).extracting(Edge::to).containsExactly(0);
    }

    @Test
    void reversedFlipsEveryEdgeAndKeepsWeights() {
        Graph reversed = TestGraphs.diamond().reversed();
        assertThat(reversed.edgeCount()).isEqualTo(6);
        assertThat(reversed.outgoing(3)).extracting(Edge::to).containsExactlyInAnyOrder(1, 4);
        assertThat(reversed.outgoing(0)).isEmpty();
        assertThat(reversed.outgoing(1).getFirst().distanceMeters()).isEqualTo(4);
    }

    @Test
    void graphIsImmutable() {
        Graph graph = TestGraphs.diamond();
        assertThatThrownBy(() -> graph.outgoing(0).add(new Edge(0, 1, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graph.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEdgeToUnknownNode() {
        assertThatThrownBy(() -> TestGraphs.nodes(2).addEdge(0, 5, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown node 5");
    }

    @Test
    void rejectsNegativeNaNAndInfiniteWeights() {
        assertThatThrownBy(() -> new Edge(0, 1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Edge(0, 1, 1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Edge(0, 1, Double.POSITIVE_INFINITY, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThatThrownBy(() -> Graph.builder().addNode(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Graph.builder().addNode(0, -181)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Graph.builder().addNode(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownNodeLookups() {
        Graph graph = TestGraphs.nodes(1).build();
        assertThatThrownBy(() -> graph.outgoing(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> graph.node(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
