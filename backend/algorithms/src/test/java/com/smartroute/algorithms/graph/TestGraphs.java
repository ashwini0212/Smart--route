package com.smartroute.algorithms.graph;

/** Small hand-built graphs whose answers can be checked on paper. */
final class TestGraphs {

    private TestGraphs() {
    }

    /** Graph with {@code n} nodes at (0,0) and no edges. Coordinates don't matter for these tests. */
    static Graph.Builder nodes(int n) {
        Graph.Builder builder = Graph.builder();
        for (int i = 0; i < n; i++) {
            builder.addNode(0, 0);
        }
        return builder;
    }

    /**
     * <pre>
     *        4        1
     *   0 ------> 1 ------> 3
     *   |                   ^
     *   | 1               1 |
     *   v         1         |
     *   2 ----------------> 4 ----> 5 (3)
     * </pre>
     * Distances: 0→1=4, 1→3=1, 0→2=1, 2→4=1, 4→3=1, 4→5=3.
     * Shortest 0→3 by distance: 0-2-4-3 (cost 3). Fewest hops 0→3: 0-1-3 (2 hops, cost 5).
     * Travel time is set so that the direct 0-1-3 route is faster (arterial).
     */
    static Graph diamond() {
        return nodes(6)
                .addEdge(0, 1, 4, 2)
                .addEdge(1, 3, 1, 1)
                .addEdge(0, 2, 1, 5)
                .addEdge(2, 4, 1, 5)
                .addEdge(4, 3, 1, 5)
                .addEdge(4, 5, 3, 3)
                .build();
    }
}
