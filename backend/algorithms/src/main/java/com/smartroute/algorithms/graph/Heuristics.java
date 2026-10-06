package com.smartroute.algorithms.graph;

/** Ready-made consistent heuristics for road graphs. */
public final class Heuristics {

    private Heuristics() {
    }

    /**
     * Straight-line (haversine) distance in metres, for {@link EdgeWeight#DISTANCE}.
     * Consistent as long as every edge is at least as long as the straight line between its endpoints,
     * which holds for real roads and for {@link com.smartroute.algorithms.graph.generator.CityGraphGenerator}.
     */
    public static Heuristic straightLineDistance(Graph graph) {
        return (node, target) -> GeoMath.haversineMeters(graph.node(node), graph.node(target));
    }

    /**
     * Straight-line distance divided by the fastest speed in the graph, for {@link EdgeWeight#TRAVEL_TIME}.
     *
     * <p>Why the <em>maximum</em> speed: the heuristic must never overestimate. Assuming you could drive
     * the whole remaining straight line at the fastest speed found on any edge gives a lower bound on
     * the real time. The tradeoff: one fast highway edge makes the bound weak everywhere, so A* explores
     * more nodes in time mode than in distance mode.
     */
    public static Heuristic straightLineTravelTime(Graph graph) {
        double maxSpeed = maxSpeedMetersPerSecond(graph);
        if (maxSpeed == Double.POSITIVE_INFINITY) {
            // A zero-time edge with positive length means "infinitely fast": the only safe bound is 0.
            return Heuristic.ZERO;
        }
        if (maxSpeed == 0) {
            return Heuristic.ZERO;
        }
        return (node, target) -> GeoMath.haversineMeters(graph.node(node), graph.node(target)) / maxSpeed;
    }

    static double maxSpeedMetersPerSecond(Graph graph) {
        double max = 0;
        for (GraphNode node : graph.nodes()) {
            for (Edge edge : graph.outgoing(node.id())) {
                if (edge.distanceMeters() == 0) {
                    continue;
                }
                if (edge.travelTimeSeconds() == 0) {
                    return Double.POSITIVE_INFINITY;
                }
                max = Math.max(max, edge.distanceMeters() / edge.travelTimeSeconds());
            }
        }
        return max;
    }
}
