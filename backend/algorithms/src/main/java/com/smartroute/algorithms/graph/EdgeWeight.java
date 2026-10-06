package com.smartroute.algorithms.graph;

/**
 * Chooses what "cost" means for a path search. The same Dijkstra/A* code answers
 * "shortest" (distance) and "fastest" (time) by swapping this function.
 *
 * <p>Implementations must return a finite, non-negative value for every edge.
 */
@FunctionalInterface
public interface EdgeWeight {

    double of(Edge edge);

    EdgeWeight DISTANCE = Edge::distanceMeters;
    EdgeWeight TRAVEL_TIME = Edge::travelTimeSeconds;
    /** Every edge costs 1; a weighted search with this weight gives the same cost as BFS. */
    EdgeWeight HOPS = edge -> 1.0;
}
