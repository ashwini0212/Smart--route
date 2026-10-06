package com.smartroute.routing;

import java.time.Instant;
import java.util.List;

/**
 * A visiting order with the legs between stops.
 *
 * @param optimal       true only when the order is proven shortest for the computed cost matrix
 * @param algorithm     which algorithm produced the order, labelled [HEURISTIC] when it is one
 * @param lateStops     stops whose {@code dueBy} cannot be met on this route; reported, never silently dropped
 * @param comparedTo    set when the client asked for both strategies: the other result's total, for the gap
 */
public record OptimizedRoute(
        RouteMode mode,
        boolean returnToStart,
        Instant departAt,
        double totalDistanceMeters,
        double totalDurationSeconds,
        double serviceSeconds,
        Instant finishAt,
        String algorithm,
        boolean optimal,
        long algorithmSteps,
        long sequencingMillis,
        int stopCount,
        List<Visit> visits,
        List<Leg> legs,
        List<Late> lateStops,
        Double comparedTo,
        long graphVersion) {

    /**
     * One stop in visiting order.
     *
     * @param stopIndex position of this stop in the request, so a client can match it back
     */
    public record Visit(int sequence, int stopIndex, String label, double latitude, double longitude,
                        Instant arriveAt, Instant departAt, double snapDistanceMeters) {
    }

    /** Travel between two consecutive points, with the road geometry for a map. */
    public record Leg(int fromSequence, int toSequence, String from, String to, double distanceMeters,
                      double durationSeconds, List<double[]> path) {
    }

    /** A stop that arrives after its deadline. */
    public record Late(int stopIndex, String label, Instant dueBy, Instant arriveAt, long lateBySeconds) {
    }
}
