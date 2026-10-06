package com.smartroute.routing;

import com.smartroute.algorithms.graph.Graph;
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
        Instant builtAt) {

    static RoadNetwork of(long version, String fingerprint, String source, boolean synthetic, Graph graph,
                          NearestNodeIndex index, Map<EdgeKey, Double> traffic, Instant builtAt) {
        return new RoadNetwork(version, fingerprint, source, synthetic, graph, index,
                Heuristics.straightLineDistance(graph), Heuristics.straightLineTravelTime(graph),
                Map.copyOf(traffic), builtAt);
    }

    /** A directed road segment between two nodes of the network. */
    public record EdgeKey(int from, int to) {
    }
}
