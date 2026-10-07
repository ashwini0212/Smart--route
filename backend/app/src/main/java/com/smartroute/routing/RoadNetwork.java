package com.smartroute.routing;

import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.algorithms.graph.Heuristic;
import com.smartroute.algorithms.graph.Heuristics;
import com.smartroute.algorithms.spatial.NearestNodeIndex;

import java.time.Instant;
import java.util.Map;

/**
 * One immutable version of the road network: graph (with traffic applied), snapping index and A*
 * heuristics. A request takes one snapshot and uses it throughout, so a traffic update in the middle of a
 * request can never mix two versions.
 *
 * @param version     increments on every change in this app instance (shown to users)
 * @param fingerprint identifies the content (dataset + traffic); used in cache keys so instances with the
 *                    same data share cache entries and a changed network never reads stale ones
 * @param source      human-readable origin, e.g. "synthetic 100x100 grid (seed 42)"
 * @param synthetic   true when the graph is generated, not real map data
 * @param traffic     current multipliers by edge (from, to); empty means free-flow
 * @param reversed    the same graph with every edge flipped (same travel times), for "how long from every
 *                    node <em>to</em> X" searches such as driver ETAs to a pickup
 * @param bounds      the corner coordinates of the whole network, computed once here. The map pages ask for
 *                    them on every load, and node positions never change within a version — only edge travel
 *                    times do — so walking 10,000+ nodes per request was work with a constant answer
 */
public record RoadNetwork(
        long version,
        String fingerprint,
        String source,
        boolean synthetic,
        Graph graph,
        NearestNodeIndex index,
        Heuristic distanceHeuristic,
        Heuristic timeHeuristic,
        Map<EdgeKey, Double> traffic,
        Graph reversed,
        Bounds bounds,
        Instant builtAt) {

    static RoadNetwork of(long version, String fingerprint, String source, boolean synthetic, Graph graph,
                          NearestNodeIndex index, Map<EdgeKey, Double> traffic, Instant builtAt) {
        return new RoadNetwork(version, fingerprint, source, synthetic, graph, index,
                Heuristics.straightLineDistance(graph), Heuristics.straightLineTravelTime(graph),
                Map.copyOf(traffic), graph.reversed(), Bounds.around(graph), builtAt);
    }

    /** The corner coordinates of every node in the network. */
    public record Bounds(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {

        static Bounds around(Graph graph) {
            double minLat = Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (GraphNode node : graph.nodes()) {
                minLat = Math.min(minLat, node.latitude());
                maxLat = Math.max(maxLat, node.latitude());
                minLon = Math.min(minLon, node.longitude());
                maxLon = Math.max(maxLon, node.longitude());
            }
            return new Bounds(minLat, minLon, maxLat, maxLon);
        }
    }

    /** A directed road segment between two nodes of the network. */
    public record EdgeKey(int from, int to) {
    }
}
