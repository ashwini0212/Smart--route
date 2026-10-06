package com.smartroute.routing;

import com.smartroute.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/** A route that was computed for a user, kept for history. Immutable once saved. */
@Entity
@Table(name = "route_record")
class RouteRecord extends BaseEntity {

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RouteMode mode;

    @Column(name = "from_latitude", nullable = false)
    private double fromLatitude;

    @Column(name = "from_longitude", nullable = false)
    private double fromLongitude;

    @Column(name = "to_latitude", nullable = false)
    private double toLatitude;

    @Column(name = "to_longitude", nullable = false)
    private double toLongitude;

    @Column(name = "from_node", nullable = false)
    private int fromNode;

    @Column(name = "to_node", nullable = false)
    private int toNode;

    @Column(name = "distance_m", nullable = false)
    private double distanceMeters;

    @Column(name = "duration_s", nullable = false)
    private double durationSeconds;

    @Column(name = "graph_version", nullable = false)
    private long graphVersion;

    @Column(nullable = false, length = 60)
    private String algorithm;

    @Column(name = "nodes_settled", nullable = false)
    private int nodesSettled;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "double precision[]")
    private double[] path;

    protected RouteRecord() {
        // for JPA
    }

    RouteRecord(long requestedBy, GeoPoint from, GeoPoint to, RoutePath route, boolean cacheHit) {
        this.requestedBy = requestedBy;
        this.mode = route.mode();
        this.fromLatitude = from.latitude();
        this.fromLongitude = from.longitude();
        this.toLatitude = to.latitude();
        this.toLongitude = to.longitude();
        this.fromNode = route.fromNode();
        this.toNode = route.toNode();
        this.distanceMeters = route.distanceMeters();
        this.durationSeconds = route.durationSeconds();
        this.graphVersion = route.graphVersion();
        this.algorithm = route.algorithm();
        this.nodesSettled = route.nodesSettled();
        this.cacheHit = cacheHit;
        this.path = new double[route.path().size() * 2];
        for (int i = 0; i < route.path().size(); i++) {
            path[2 * i] = route.path().get(i)[0];
            path[2 * i + 1] = route.path().get(i)[1];
        }
    }

    List<double[]> pathPoints() {
        List<double[]> points = new ArrayList<>(path.length / 2);
        for (int i = 0; i + 1 < path.length; i += 2) {
            points.add(new double[] {path[i], path[i + 1]});
        }
        return points;
    }

    Long getRequestedBy() {
        return requestedBy;
    }

    RouteMode getMode() {
        return mode;
    }

    GeoPoint from() {
        return new GeoPoint(fromLatitude, fromLongitude);
    }

    GeoPoint to() {
        return new GeoPoint(toLatitude, toLongitude);
    }

    int getFromNode() {
        return fromNode;
    }

    int getToNode() {
        return toNode;
    }

    double getDistanceMeters() {
        return distanceMeters;
    }

    double getDurationSeconds() {
        return durationSeconds;
    }

    long getGraphVersion() {
        return graphVersion;
    }

    String getAlgorithm() {
        return algorithm;
    }

    int getNodesSettled() {
        return nodesSettled;
    }

    boolean isCacheHit() {
        return cacheHit;
    }
}
