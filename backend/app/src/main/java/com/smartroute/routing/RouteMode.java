package com.smartroute.routing;

import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.Heuristic;

/** What "best" means for a route. */
public enum RouteMode {
    /** Fewest metres. */
    SHORTEST,
    /** Fewest seconds, using current traffic. */
    FASTEST;

    EdgeWeight weight() {
        return this == SHORTEST ? EdgeWeight.DISTANCE : EdgeWeight.TRAVEL_TIME;
    }

    Heuristic heuristic(RoadNetwork network) {
        return this == SHORTEST ? network.distanceHeuristic() : network.timeHeuristic();
    }
}
