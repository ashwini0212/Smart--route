package com.smartroute.algorithms.graph;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * A* search: Dijkstra that orders the heap by {@code g(n) + h(n)} instead of {@code g(n)}, where g is
 * the cost so far and h a lower-bound estimate of the remaining cost (see {@link Heuristic}).
 *
 * <p>The heuristic pulls the search toward the target, so it usually settles far fewer nodes than
 * Dijkstra, which expands in a circle around the source. How many fewer depends on the graph and on
 * how tight h is; this is measured in the benchmarks, not assumed.
 *
 * <p>Correctness: with a consistent heuristic, the first time a node is polled its g is optimal, so the
 * same settled/lazy-deletion scheme as {@link Dijkstra} is valid and the returned path is optimal.
 *
 * <p>Complexity: worst case equals Dijkstra, O((V + E) log V) time (h = 0 is exactly Dijkstra), plus the
 * cost of evaluating h once per push. Space O(V + E).
 */
public final class AStar {

    private AStar() {
    }

    public static Path shortestPath(Graph graph, int source, int target, EdgeWeight weight, Heuristic heuristic) {
        graph.requireNode(source);
        graph.requireNode(target);
        int n = graph.nodeCount();
        double[] g = new double[n];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        Edge[] parentEdge = new Edge[n];
        boolean[] settled = new boolean[n];

        PriorityQueue<QueueEntry> heap = new PriorityQueue<>(QueueEntry.ORDER);
        g[source] = 0;
        heap.add(new QueueEntry(source, heuristic.estimate(source, target)));
        int settledCount = 0;

        while (!heap.isEmpty()) {
            int u = heap.poll().node();
            if (settled[u]) {
                continue;
            }
            settled[u] = true;
            settledCount++;
            if (u == target) {
                return PathReconstruction.build(source, target, parentEdge, g[target], settledCount);
            }
            for (Edge edge : graph.outgoing(u)) {
                int v = edge.to();
                if (settled[v]) {
                    continue;
                }
                double candidate = g[u] + Dijkstra.checkedWeight(weight, edge);
                if (candidate < g[v]) {
                    g[v] = candidate;
                    parentEdge[v] = edge;
                    heap.add(new QueueEntry(v, candidate + heuristic.estimate(v, target)));
                }
            }
        }
        return Path.unreachable(settledCount);
    }
}
