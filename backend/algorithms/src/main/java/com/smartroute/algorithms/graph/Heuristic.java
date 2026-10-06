package com.smartroute.algorithms.graph;

/**
 * A* heuristic h(node, target): an estimate of the remaining cost.
 *
 * <p>For A* to return optimal paths with the closed-set implementation in {@link AStar}, the heuristic
 * must be <b>consistent</b>: {@code h(u) <= w(u,v) + h(v)} for every edge, and {@code h(target) = 0}.
 * Consistent implies admissible (never overestimates).
 */
@FunctionalInterface
public interface Heuristic {

    double estimate(int nodeId, int targetId);

    /** h = 0 turns A* into Dijkstra. Always consistent. */
    Heuristic ZERO = (node, target) -> 0.0;
}
