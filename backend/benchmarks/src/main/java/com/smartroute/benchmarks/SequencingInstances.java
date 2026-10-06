package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.Dijkstra;
import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.ShortestPathTree;
import com.smartroute.algorithms.sequencing.CostMatrix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Builds road-travel-time matrices over random points of a city graph, the way the API does. */
final class SequencingInstances {

    private SequencingInstances() {
    }

    /** @param stops stops besides the start, so the matrix has {@code stops + 1} points */
    static CostMatrix travelTimeMatrix(Graph city, int stops, long seed) {
        Random random = new Random(seed * 1000L + stops);
        Set<Integer> nodes = new LinkedHashSet<>();
        while (nodes.size() < stops + 1) {
            nodes.add(random.nextInt(city.nodeCount()));
        }
        List<Integer> points = new ArrayList<>(nodes);
        double[][] cost = new double[points.size()][points.size()];
        for (int i = 0; i < points.size(); i++) {
            ShortestPathTree tree = Dijkstra.shortestPathTree(city, points.get(i), EdgeWeight.TRAVEL_TIME, points);
            for (int j = 0; j < points.size(); j++) {
                cost[i][j] = i == j ? 0
                        : tree.isReachable(points.get(j)) ? tree.costTo(points.get(j)) : Double.POSITIVE_INFINITY;
            }
        }
        return CostMatrix.of(cost);
    }
}
