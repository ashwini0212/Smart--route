package com.smartroute.routing;

import java.util.List;

/**
 * A computed route between two network nodes. This is what the cache stores, so it contains nothing
 * request-specific (no ids, no exact input coordinates).
 *
 * @param path           [latitude, longitude] of every node along the route, ready for a map polyline
 * @param durationSeconds travel time including the traffic of {@code graphVersion}
 * @param algorithm      e.g. "A*" (optimal) or "penalty alternatives [HEURISTIC]"
 */
public record RoutePath(
        RouteMode mode,
        int fromNode,
        int toNode,
        double distanceMeters,
        double durationSeconds,
        List<double[]> path,
        String algorithm,
        boolean optimal,
        int nodesSettled,
        long graphVersion) {
}
