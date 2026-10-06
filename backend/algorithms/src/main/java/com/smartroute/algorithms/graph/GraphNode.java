package com.smartroute.algorithms.graph;

/**
 * A vertex of the road graph: one intersection (or end of a road) with a geographic position.
 *
 * @param id        dense index in {@code [0, nodeCount)}; algorithms use it to index arrays
 * @param latitude  WGS84 latitude in degrees, {@code [-90, 90]}
 * @param longitude WGS84 longitude in degrees, {@code [-180, 180]}
 */
public record GraphNode(int id, double latitude, double longitude) {

    public GraphNode {
        if (id < 0) {
            throw new IllegalArgumentException("Node id must be non-negative: " + id);
        }
        if (!(latitude >= -90 && latitude <= 90)) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (!(longitude >= -180 && longitude <= 180)) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
    }
}
