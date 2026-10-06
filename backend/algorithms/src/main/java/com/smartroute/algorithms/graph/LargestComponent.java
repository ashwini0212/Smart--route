package com.smartroute.algorithms.graph;

import java.util.Arrays;

/**
 * Restricts a graph to its largest strongly connected component and renumbers the nodes.
 *
 * <p>Why: inside one SCC every node can reach every other node, so after this step any two snapped
 * locations always have a route both ways. The removed nodes (dead ends, isolated pieces caused by
 * one-way tags or the bounding-box cut) are reported, not silently dropped.
 *
 * <p>Time O(V + E) (SCC + one pass to copy edges). Space O(V + E).
 *
 * @param graph        the restricted graph with dense ids {@code 0..k-1}
 * @param originalIdOf {@code originalIdOf[newId]} = id in the input graph
 * @param newIdOf      {@code newIdOf[originalId]} = new id, or -1 if the node was removed
 */
public record LargestComponent(Graph graph, int[] originalIdOf, int[] newIdOf) {

    public static LargestComponent of(Graph input) {
        StronglyConnectedComponents scc = StronglyConnectedComponents.of(input);
        int[] newIdOf = new int[input.nodeCount()];
        Arrays.fill(newIdOf, -1);
        if (input.isEmpty()) {
            return new LargestComponent(input, new int[0], newIdOf);
        }

        int keep = scc.largestComponent();
        Graph.Builder builder = Graph.builder();
        int[] originalIdOf = new int[scc.sizeOf(keep)];
        for (GraphNode node : input.nodes()) {
            if (scc.componentOf(node.id()) == keep) {
                int newId = builder.addNode(node.latitude(), node.longitude());
                newIdOf[node.id()] = newId;
                originalIdOf[newId] = node.id();
            }
        }
        for (GraphNode node : input.nodes()) {
            int from = newIdOf[node.id()];
            if (from == -1) {
                continue;
            }
            for (Edge edge : input.outgoing(node.id())) {
                int to = newIdOf[edge.to()];
                if (to != -1) {
                    builder.addEdge(from, to, edge.distanceMeters(), edge.travelTimeSeconds());
                }
            }
        }
        return new LargestComponent(builder.build(), originalIdOf, newIdOf);
    }

    public int removedNodeCount() {
        return newIdOf.length - originalIdOf.length;
    }
}
