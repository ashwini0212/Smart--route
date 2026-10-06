package com.smartroute.algorithms.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LargestComponentTest {

    @Test
    void keepsOnlyTheLargestStronglyConnectedComponentAndRenumbers() {
        // {1,2,3} is a cycle (kept). 0 has a road into the cycle but none back to it (removed).
        // 4 is reachable from 3 but has no way back (dead end, removed).
        Graph graph = TestGraphs.nodes(5)
                .addEdge(0, 1, 1, 1)
                .addEdge(1, 2, 2, 2).addEdge(2, 3, 3, 3).addEdge(3, 1, 4, 4)
                .addEdge(3, 4, 5, 5)
                .build();
        LargestComponent result = LargestComponent.of(graph);

        assertThat(result.graph().nodeCount()).isEqualTo(3);
        assertThat(result.graph().edgeCount()).isEqualTo(3);
        assertThat(result.removedNodeCount()).isEqualTo(2);
        assertThat(result.originalIdOf()).containsExactly(1, 2, 3);
        assertThat(result.newIdOf()).containsExactly(-1, 0, 1, 2, -1);
        assertThat(result.graph().outgoing(0).getFirst().distanceMeters()).isEqualTo(2);
    }

    @Test
    void everyPairIsReachableAfterRestriction() {
        Graph city = com.smartroute.algorithms.graph.generator.CityGraphGenerator.generate(
                com.smartroute.algorithms.graph.generator.CityGraphConfig.grid(25, 25, 11));
        Graph strong = LargestComponent.of(city).graph();
        boolean[] fromZero = DepthFirstSearch.reachableFrom(strong, 0);
        boolean[] toZero = DepthFirstSearch.reachableFrom(strong.reversed(), 0);
        for (int v = 0; v < strong.nodeCount(); v++) {
            assertThat(fromZero[v] && toZero[v]).isTrue();
        }
    }

    @Test
    void emptyGraphStaysEmpty() {
        LargestComponent result = LargestComponent.of(Graph.builder().build());
        assertThat(result.graph().isEmpty()).isTrue();
        assertThat(result.removedNodeCount()).isZero();
    }
}
