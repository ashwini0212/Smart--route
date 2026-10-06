package com.smartroute.algorithms.graph;

/**
 * A directed road segment from {@code from} to {@code to}.
 * A two-way street is stored as two edges, one per direction.
 *
 * @param from              source node id
 * @param to                target node id
 * @param distanceMeters    physical length, {@code >= 0}
 * @param travelTimeSeconds free-flow travel time, {@code >= 0}
 */
public record Edge(int from, int to, double distanceMeters, double travelTimeSeconds) {

    public Edge {
        if (from < 0 || to < 0) {
            throw new IllegalArgumentException("Edge endpoints must be non-negative: " + from + " -> " + to);
        }
        requireNonNegativeFinite(distanceMeters, "distanceMeters");
        requireNonNegativeFinite(travelTimeSeconds, "travelTimeSeconds");
    }

    /** The same road driven the other way, used to build the reversed graph. */
    public Edge reversed() {
        return new Edge(to, from, distanceMeters, travelTimeSeconds);
    }

    private static void requireNonNegativeFinite(double value, String name) {
        // Dijkstra and A* are only correct with non-negative weights, so reject bad data at the boundary.
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative: " + value);
        }
    }
}
