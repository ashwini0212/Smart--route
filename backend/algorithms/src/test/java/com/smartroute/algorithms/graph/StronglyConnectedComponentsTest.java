package com.smartroute.algorithms.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StronglyConnectedComponentsTest {

    @Test
    void emptyGraphHasNoComponents() {
        StronglyConnectedComponents scc = StronglyConnectedComponents.of(Graph.builder().build());
        assertThat(scc.componentCount()).isZero();
        assertThat(scc.largestComponent()).isEqualTo(-1);
        assertThat(scc.nodesInLargestComponent()).isEmpty();
    }

    @Test
    void singleNodeIsItsOwnComponent() {
        StronglyConnectedComponents scc = StronglyConnectedComponents.of(TestGraphs.nodes(1).build());
        assertThat(scc.componentCount()).isEqualTo(1);
        assertThat(scc.sizeOf(scc.largestComponent())).isEqualTo(1);
    }

    @Test
    void findsClassicComponents() {
        // {0,1,2} is a cycle, {3,4} is a cycle, 5 is a dead end reachable from 4.
        Graph graph = TestGraphs.nodes(6)
                .addEdge(0, 1, 1, 1).addEdge(1, 2, 1, 1).addEdge(2, 0, 1, 1)
                .addEdge(2, 3, 1, 1)
                .addEdge(3, 4, 1, 1).addEdge(4, 3, 1, 1)
                .addEdge(4, 5, 1, 1)
                .build();
        StronglyConnectedComponents scc = StronglyConnectedComponents.of(graph);

        assertThat(scc.componentCount()).isEqualTo(3);
        assertThat(scc.sameComponent(0, 2)).isTrue();
        assertThat(scc.sameComponent(3, 4)).isTrue();
        assertThat(scc.sameComponent(2, 3)).isFalse(); // 2 reaches 3, but 3 can't get back
        assertThat(scc.sameComponent(4, 5)).isFalse();
        assertThat(scc.nodesInLargestComponent()).containsExactly(0, 1, 2);
    }

    @Test
    void directedAcyclicGraphHasOneComponentPerNode() {
        Graph dag = TestGraphs.nodes(4).addEdge(0, 1, 1, 1).addEdge(1, 2, 1, 1).addEdge(0, 3, 1, 1).build();
        assertThat(StronglyConnectedComponents.of(dag).componentCount()).isEqualTo(4);
    }

    @Test
    void handlesVeryLongPathsWithoutStackOverflow() {
        // A recursive DFS would need 200,000 nested calls here and overflow the default thread stack.
        int n = 200_000;
        Graph.Builder builder = TestGraphs.nodes(n);
        for (int i = 0; i + 1 < n; i++) {
            builder.addEdge(i, i + 1, 1, 1);
        }
        builder.addEdge(n - 1, 0, 1, 1); // close the ring so everything is one SCC
        StronglyConnectedComponents scc = StronglyConnectedComponents.of(builder.build());
        assertThat(scc.componentCount()).isEqualTo(1);
        assertThat(scc.sizeOf(0)).isEqualTo(n);
    }

    @Test
    void dfsReachabilityFollowsEdgeDirection() {
        boolean[] reachable = DepthFirstSearch.reachableFrom(TestGraphs.diamond(), 2);
        assertThat(reachable).containsExactly(false, false, true, true, true, true);
    }
}
