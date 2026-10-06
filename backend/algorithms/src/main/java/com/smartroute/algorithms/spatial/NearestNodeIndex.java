package com.smartroute.algorithms.spatial;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;

import java.util.Arrays;
import java.util.Optional;

/**
 * 2-d tree (k-d tree with k = 2) for "which graph node is closest to this GPS point?".
 *
 * <p>Problem: an order's address and a driver's GPS position are arbitrary coordinates; routing needs
 * a graph node. Checking every node is O(n) per lookup. A k-d tree answers in O(log n) on average.
 *
 * <p>How it works: points are projected to a flat x/y plane in metres (equirectangular projection
 * around the graph's mean latitude, accurate to well under 1 % across a city). The tree splits on x at
 * even depths and y at odd depths, always at the median, so it is balanced. A query walks down to the
 * leaf region containing the point, then backtracks, visiting the other side of a split only when the
 * splitting line is closer than the best distance found so far.
 *
 * <p>Complexity: build O(n log² n) (a sort per level); query O(log n) average, O(n) worst case
 * (pathological layouts); space O(n).
 *
 * <p>Alternatives: a uniform grid (simpler, great when points are evenly spread, bad with dense
 * clusters); Redis GEO / PostGIS (used for driver positions in later phases, but routing needs the
 * graph's own nodes in memory).
 */
public final class NearestNodeIndex {

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;

    private final Graph graph;
    private final int[] tree;      // node ids arranged as an implicit balanced tree over index ranges
    private final double[] x;      // projected coordinates, indexed by node id
    private final double[] y;
    private final double referenceLatitudeCos;

    private NearestNodeIndex(Graph graph) {
        this.graph = graph;
        int n = graph.nodeCount();
        double meanLatitude = graph.nodes().stream().mapToDouble(GraphNode::latitude).average().orElse(0);
        this.referenceLatitudeCos = Math.cos(Math.toRadians(meanLatitude));
        this.x = new double[n];
        this.y = new double[n];
        for (GraphNode node : graph.nodes()) {
            x[node.id()] = projectX(node.longitude());
            y[node.id()] = projectY(node.latitude());
        }
        Integer[] ids = new Integer[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
        }
        build(ids, 0, n, 0);
        this.tree = Arrays.stream(ids).mapToInt(Integer::intValue).toArray();
    }

    public static NearestNodeIndex of(Graph graph) {
        return new NearestNodeIndex(graph);
    }

    /** A graph node and its great-circle distance from the query point. */
    public record Nearest(int nodeId, double distanceMeters) {
    }

    /** Closest node to (latitude, longitude), or empty for an empty graph. Ties go to the lower node id. */
    public Optional<Nearest> nearest(double latitude, double longitude) {
        if (tree.length == 0) {
            return Optional.empty();
        }
        Search search = new Search(projectX(longitude), projectY(latitude));
        search.visit(0, tree.length, 0);
        GraphNode node = graph.node(search.bestId);
        return Optional.of(new Nearest(search.bestId,
                GeoMath.haversineMeters(latitude, longitude, node.latitude(), node.longitude())));
    }

    private void build(Integer[] ids, int from, int to, int depth) {
        if (to - from <= 1) {
            return;
        }
        boolean splitOnX = depth % 2 == 0;
        Arrays.sort(ids, from, to, (a, b) -> splitOnX
                ? Double.compare(x[a], x[b]) != 0 ? Double.compare(x[a], x[b]) : Integer.compare(a, b)
                : Double.compare(y[a], y[b]) != 0 ? Double.compare(y[a], y[b]) : Integer.compare(a, b));
        int mid = (from + to) >>> 1;
        build(ids, from, mid, depth + 1);
        build(ids, mid + 1, to, depth + 1);
    }

    private double projectX(double longitude) {
        return longitude * METERS_PER_DEGREE_LATITUDE * referenceLatitudeCos;
    }

    private double projectY(double latitude) {
        return latitude * METERS_PER_DEGREE_LATITUDE;
    }

    /** Mutable state of one query (keeps the index itself immutable and thread-safe). */
    private final class Search {
        private final double qx;
        private final double qy;
        private int bestId = -1;
        private double bestSquaredDistance = Double.POSITIVE_INFINITY;

        Search(double qx, double qy) {
            this.qx = qx;
            this.qy = qy;
        }

        void visit(int from, int to, int depth) {
            if (from >= to) {
                return;
            }
            int mid = (from + to) >>> 1;
            int id = tree[mid];
            double dx = x[id] - qx;
            double dy = y[id] - qy;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestSquaredDistance || (d2 == bestSquaredDistance && id < bestId)) {
                bestSquaredDistance = d2;
                bestId = id;
            }
            double delta = depth % 2 == 0 ? qx - x[id] : qy - y[id];
            // Search the side containing the query first; it is most likely to hold the answer.
            boolean leftFirst = delta < 0;
            if (leftFirst) {
                visit(from, mid, depth + 1);
            } else {
                visit(mid + 1, to, depth + 1);
            }
            // The other side can only contain something closer if the splitting line is within reach.
            if (delta * delta <= bestSquaredDistance) {
                if (leftFirst) {
                    visit(mid + 1, to, depth + 1);
                } else {
                    visit(from, mid, depth + 1);
                }
            }
        }
    }
}
