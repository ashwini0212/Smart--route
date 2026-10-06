package com.smartroute.routing;

import java.time.Instant;
import java.util.List;

/**
 * A route as returned by the API.
 *
 * @param id       history id (null for alternatives, which are not stored)
 * @param from     where the start point was attached to the road network, and how far away it was
 * @param cached   true when served from the cache instead of computed for this request
 * @param optimal  true for A* results; false for heuristic alternatives
 */
public record RouteResponse(
        Long id,
        RouteMode mode,
        double distanceMeters,
        double durationSeconds,
        List<double[]> path,
        RouteEngine.SnappedPoint from,
        RouteEngine.SnappedPoint to,
        String algorithm,
        boolean optimal,
        int nodesSettled,
        long graphVersion,
        boolean cached,
        Instant createdAt) {

    static RouteResponse of(Long id, RoutePath route, RouteEngine.SnappedPoint from, RouteEngine.SnappedPoint to,
                            boolean cached, Instant createdAt) {
        return new RouteResponse(id, route.mode(), route.distanceMeters(), route.durationSeconds(), route.path(),
                from, to, route.algorithm(), route.optimal(), route.nodesSettled(), route.graphVersion(), cached, createdAt);
    }
}
