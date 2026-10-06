package com.smartroute.algorithms.graph;

import java.util.List;

/**
 * Result of a point-to-point search.
 *
 * @param nodeIds      nodes from source to target inclusive; empty when the target is unreachable
 * @param edges        edges actually used, in order (needed because parallel edges can differ)
 * @param cost         total cost under the search's {@link EdgeWeight}; {@code +∞} when unreachable
 * @param nodesSettled how many nodes the search finalized; measures search effort (Dijkstra vs A*)
 */
public record Path(List<Integer> nodeIds, List<Edge> edges, double cost, int nodesSettled) {

    public Path {
        nodeIds = List.copyOf(nodeIds);
        edges = List.copyOf(edges);
    }

    public static Path unreachable(int nodesSettled) {
        return new Path(List.of(), List.of(), Double.POSITIVE_INFINITY, nodesSettled);
    }

    public boolean isFound() {
        return !nodeIds.isEmpty();
    }

    public double totalDistanceMeters() {
        return edges.stream().mapToDouble(Edge::distanceMeters).sum();
    }

    public double totalTravelTimeSeconds() {
        return edges.stream().mapToDouble(Edge::travelTimeSeconds).sum();
    }

    /** Number of road segments (0 when source equals target). */
    public int hopCount() {
        return edges.size();
    }
}
