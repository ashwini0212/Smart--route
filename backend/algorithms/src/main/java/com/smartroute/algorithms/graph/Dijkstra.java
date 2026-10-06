package com.smartroute.algorithms.graph;

import java.util.Arrays;
import java.util.Collection;
import java.util.PriorityQueue;

/**
 * Dijkstra's algorithm with a binary heap ({@link PriorityQueue}) and lazy deletion.
 *
 * <p>Invariant: when a node is polled from the heap for the first time, its cost is final. This
 * holds only because all edge weights are non-negative (no later path can become cheaper), which is
 * why {@link Edge} rejects negative values.
 *
 * <p><b>Lazy deletion:</b> {@code java.util.PriorityQueue} has no efficient decrease-key. Instead of
 * updating an entry, we push a new entry with the lower cost and skip stale entries when they are
 * polled (the node is already settled). The heap can therefore hold up to O(E) entries.
 *
 * <p><b>Complexity (this implementation):</b> each edge relaxation may push once → at most E pushes
 * and E polls, each O(log E). Since E ≤ V², log E ≤ 2 log V, so time is O((V + E) log V).
 * Space O(V + E): arrays of size V plus up to E heap entries.
 *
 * <p>Alternatives: a Fibonacci heap gives O(E + V log V) but has large constants and is rarely faster
 * in practice; an indexed heap with decrease-key keeps the heap at O(V) entries at the cost of more code.
 */
public final class Dijkstra {

    private Dijkstra() {
    }

    /** Cheapest path from source to target; stops as soon as the target is settled. */
    public static Path shortestPath(Graph graph, int source, int target, EdgeWeight weight) {
        graph.requireNode(target);
        ShortestPathTree tree = search(graph, source, weight, new int[] {target});
        return tree.pathTo(target);
    }

    /** Full shortest-path tree from {@code source} to every reachable node. */
    public static ShortestPathTree shortestPathTree(Graph graph, int source, EdgeWeight weight) {
        return search(graph, source, weight, null);
    }

    /**
     * One-to-many search that stops once every node in {@code targets} is settled (or the reachable
     * part of the graph is exhausted). Used to get ETAs to many candidates with a single run.
     */
    public static ShortestPathTree shortestPathTree(
            Graph graph, int source, EdgeWeight weight, Collection<Integer> targets) {
        int[] targetIds = targets.stream().mapToInt(Integer::intValue).toArray();
        for (int t : targetIds) {
            graph.requireNode(t);
        }
        return search(graph, source, weight, targetIds);
    }

    private static ShortestPathTree search(Graph graph, int source, EdgeWeight weight, int[] stopTargets) {
        graph.requireNode(source);
        int n = graph.nodeCount();
        double[] cost = new double[n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Edge[] parentEdge = new Edge[n];
        boolean[] settled = new boolean[n];
        boolean[] isTarget = null;
        int targetsRemaining = 0;
        if (stopTargets != null) {
            isTarget = new boolean[n];
            for (int t : stopTargets) {
                if (!isTarget[t]) {
                    isTarget[t] = true;
                    targetsRemaining++;
                }
            }
        }

        PriorityQueue<QueueEntry> heap = new PriorityQueue<>(QueueEntry.ORDER);
        cost[source] = 0;
        heap.add(new QueueEntry(source, 0));
        int settledCount = 0;

        while (!heap.isEmpty()) {
            QueueEntry entry = heap.poll();
            int u = entry.node();
            if (settled[u]) {
                continue; // stale entry left behind by lazy deletion
            }
            settled[u] = true;
            settledCount++;

            if (isTarget != null) {
                if (isTarget[u]) {
                    targetsRemaining--;
                }
                if (targetsRemaining == 0) {
                    break; // every requested target has its final cost
                }
            }

            for (Edge edge : graph.outgoing(u)) {
                int v = edge.to();
                if (settled[v]) {
                    continue;
                }
                double candidate = cost[u] + checkedWeight(weight, edge);
                if (candidate < cost[v]) {
                    cost[v] = candidate;
                    parentEdge[v] = edge;
                    heap.add(new QueueEntry(v, candidate));
                }
            }
        }
        return new ShortestPathTree(source, cost, parentEdge, settled, settledCount);
    }

    static double checkedWeight(EdgeWeight weight, Edge edge) {
        double w = weight.of(edge);
        if (!Double.isFinite(w) || w < 0) {
            throw new IllegalArgumentException("Edge weight must be finite and non-negative, got " + w + " for " + edge);
        }
        return w;
    }
}
