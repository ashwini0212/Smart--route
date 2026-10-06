package com.smartroute.algorithms.graph;

/**
 * Iterative depth-first search.
 *
 * <p>Why iterative: a recursive DFS uses one stack frame per node on the current path. A city road
 * graph can have paths tens of thousands of nodes long, which overflows the default JVM thread stack
 * (StackOverflowError). An explicit stack on the heap has no such limit.
 *
 * <p>Time O(V + E), space O(V).
 */
public final class DepthFirstSearch {

    private DepthFirstSearch() {
    }

    /** {@code result[v]} is true when v can be reached from {@code source} by following edge directions. */
    public static boolean[] reachableFrom(Graph graph, int source) {
        graph.requireNode(source);
        boolean[] visited = new boolean[graph.nodeCount()];
        int[] stack = new int[graph.nodeCount()];
        int size = 0;

        visited[source] = true;
        stack[size++] = source;
        while (size > 0) {
            int u = stack[--size];
            for (Edge edge : graph.outgoing(u)) {
                int v = edge.to();
                if (!visited[v]) {
                    // Marking on push (not pop) keeps each node on the stack at most once, so n slots suffice.
                    visited[v] = true;
                    stack[size++] = v;
                }
            }
        }
        return visited;
    }
}
