package com.smartroute.routing;

import java.time.Instant;

/** What road network the server is routing on. */
public record NetworkResponse(long version, String source, boolean synthetic, int nodes, int edges,
                              int segmentsWithTraffic, Bounds bounds, Instant builtAt) {

    public record Bounds(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {
    }
}
