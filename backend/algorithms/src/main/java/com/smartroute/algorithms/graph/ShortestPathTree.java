package com.smartroute.algorithms.graph;

/**
 * Result of a one-to-many Dijkstra run: best known cost and predecessor edge for every reached node.
 *
 * <p>Costs are exact for nodes that were settled. When the run stopped early (all requested targets
 * settled), nodes that were never settled report {@code +∞}.
 */
public final class ShortestPathTree {

    private final int source;
    private final double[] cost;
    private final Edge[] parentEdge;
    private final boolean[] settled;
    private final int settledCount;

    ShortestPathTree(int source, double[] cost, Edge[] parentEdge, boolean[] settled, int settledCount) {
        this.source = source;
        this.cost = cost;
        this.parentEdge = parentEdge;
        this.settled = settled;
        this.settledCount = settledCount;
    }

    public int source() {
        return source;
    }

    public boolean isReachable(int nodeId) {
        return settled[nodeId];
    }

    /** Exact shortest cost from the source, or {@code +∞} if not reached/settled. */
    public double costTo(int nodeId) {
        return settled[nodeId] ? cost[nodeId] : Double.POSITIVE_INFINITY;
    }

    public Path pathTo(int nodeId) {
        if (!settled[nodeId]) {
            return Path.unreachable(settledCount);
        }
        return PathReconstruction.build(source, nodeId, parentEdge, cost[nodeId], settledCount);
    }

    public int settledCount() {
        return settledCount;
    }
}
