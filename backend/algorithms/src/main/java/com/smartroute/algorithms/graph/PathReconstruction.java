package com.smartroute.algorithms.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Walks predecessor edges back from the target to rebuild a path. O(path length). */
final class PathReconstruction {

    private PathReconstruction() {
    }

    /**
     * @param parentEdge for every reached node except the source, the edge used to reach it
     */
    static Path build(int source, int target, Edge[] parentEdge, double cost, int nodesSettled) {
        List<Edge> edges = new ArrayList<>();
        int current = target;
        while (current != source) {
            Edge edge = parentEdge[current];
            if (edge == null) {
                return Path.unreachable(nodesSettled);
            }
            edges.add(edge);
            current = edge.from();
        }
        Collections.reverse(edges);

        List<Integer> nodeIds = new ArrayList<>(edges.size() + 1);
        nodeIds.add(source);
        for (Edge edge : edges) {
            nodeIds.add(edge.to());
        }
        return new Path(nodeIds, edges, cost, nodesSettled);
    }
}
