package com.smartroute.algorithms.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Breadth-first search: explores nodes in order of hop count (number of road segments).
 *
 * <p>BFS is only "shortest" when every edge costs the same. Roads have different lengths, so routes
 * use {@link Dijkstra}. BFS is used where hop count is the real question:
 * <ul>
 *   <li>{@link #fewestHops}: the route with the fewest segments (fewest turns/junctions).</li>
 *   <li>{@link #withinHops}: the local neighbourhood of a node, e.g. candidate junctions near a stop.</li>
 * </ul>
 *
 * <p>Time O(V + E): every node is enqueued at most once and every edge looked at at most once.
 * Space O(V) for the visited/parent arrays and the queue.
 */
public final class BreadthFirstSearch {

    private BreadthFirstSearch() {
    }

    /** Path with the fewest edges from source to target, or {@link Path#unreachable} if none exists. */
    public static Path fewestHops(Graph graph, int source, int target) {
        graph.requireNode(source);
        graph.requireNode(target);

        int n = graph.nodeCount();
        Edge[] parentEdge = new Edge[n];
        boolean[] visited = new boolean[n];
        int[] queue = new int[n]; // each node enters the queue at most once, so n slots are enough
        int head = 0;
        int tail = 0;

        visited[source] = true;
        queue[tail++] = source;
        while (head < tail) {
            int u = queue[head++];
            if (u == target) {
                // First time we dequeue the target is via a shortest (in hops) path.
                Path path = PathReconstruction.build(source, target, parentEdge, 0, head);
                return new Path(path.nodeIds(), path.edges(), path.hopCount(), head);
            }
            for (Edge edge : graph.outgoing(u)) {
                int v = edge.to();
                if (!visited[v]) {
                    visited[v] = true;
                    parentEdge[v] = edge;
                    queue[tail++] = v;
                }
            }
        }
        return Path.unreachable(head);
    }

    /**
     * All nodes reachable from {@code source} in at most {@code maxHops} edges, in BFS order
     * (source first). Stops expanding at the hop limit, so on a big graph it only touches the neighbourhood.
     */
    public static List<Integer> withinHops(Graph graph, int source, int maxHops) {
        graph.requireNode(source);
        if (maxHops < 0) {
            throw new IllegalArgumentException("maxHops must be >= 0: " + maxHops);
        }
        int n = graph.nodeCount();
        int[] hops = new int[n];
        Arrays.fill(hops, -1);
        List<Integer> order = new ArrayList<>();

        hops[source] = 0;
        order.add(source);
        for (int head = 0; head < order.size(); head++) {
            int u = order.get(head);
            if (hops[u] == maxHops) {
                continue;
            }
            for (Edge edge : graph.outgoing(u)) {
                int v = edge.to();
                if (hops[v] == -1) {
                    hops[v] = hops[u] + 1;
                    order.add(v);
                }
            }
        }
        return order;
    }
}
