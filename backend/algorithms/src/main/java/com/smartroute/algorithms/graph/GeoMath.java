package com.smartroute.algorithms.graph;

/** Great-circle math on WGS84 coordinates. */
public final class GeoMath {

    /** Mean Earth radius (IUGG), metres. */
    public static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoMath() {
    }

    /**
     * Haversine distance: the shortest distance over the Earth's surface between two points.
     * No road can be shorter than this, which is what makes it a safe A* heuristic.
     */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double haversineMeters(GraphNode a, GraphNode b) {
        return haversineMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }
}
