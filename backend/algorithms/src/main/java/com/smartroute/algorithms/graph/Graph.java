package com.smartroute.algorithms.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable directed graph stored as an adjacency list.
 *
 * <p>Why an adjacency list: road networks are sparse (an intersection has 2–4 roads), so
 * {@code E = O(V)}. An adjacency matrix would need {@code V²} cells, about 10⁸ for 10,000 nodes,
 * almost all empty. The adjacency list needs {@code O(V + E)} memory and lists a node's neighbours in
 * {@code O(degree)}.
 *
 * <p>Immutability means many threads can run searches on the same instance without locks. A change
 * (for example traffic) produces a new Graph instead of mutating this one.
 */
public final class Graph {

    private final List<GraphNode> nodes;
    private final List<List<Edge>> outgoing;
    private final int edgeCount;

    private Graph(List<GraphNode> nodes, List<List<Edge>> outgoing, int edgeCount) {
        this.nodes = nodes;
        this.outgoing = outgoing;
        this.edgeCount = edgeCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edgeCount;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public GraphNode node(int id) {
        requireNode(id);
        return nodes.get(id);
    }

    public List<GraphNode> nodes() {
        return nodes;
    }

    /** Edges leaving {@code nodeId}. O(1); the returned list is unmodifiable. */
    public List<Edge> outgoing(int nodeId) {
        requireNode(nodeId);
        return outgoing.get(nodeId);
    }

    public boolean containsNode(int nodeId) {
        return nodeId >= 0 && nodeId < nodes.size();
    }

    /**
     * Same nodes, every edge flipped. Searching the reversed graph from X answers
     * "how far is every node <em>to</em> X", which is what driver ETA to a pickup needs. O(V + E).
     */
    public Graph reversed() {
        Builder builder = builder();
        nodes.forEach(n -> builder.addNode(n.latitude(), n.longitude()));
        for (List<Edge> edges : outgoing) {
            for (Edge edge : edges) {
                builder.addEdge(edge.reversed());
            }
        }
        return builder.build();
    }

    void requireNode(int nodeId) {
        if (!containsNode(nodeId)) {
            throw new IllegalArgumentException(
                    "Unknown node id " + nodeId + " (graph has " + nodes.size() + " nodes)");
        }
    }

    /** Collects nodes and edges, validates them, and freezes them into an immutable Graph. */
    public static final class Builder {

        private final List<GraphNode> nodes = new ArrayList<>();
        private final List<List<Edge>> outgoing = new ArrayList<>();
        private int edgeCount;

        private Builder() {
        }

        /** Adds a node and returns its id (ids are assigned densely: 0, 1, 2, ...). */
        public int addNode(double latitude, double longitude) {
            int id = nodes.size();
            nodes.add(new GraphNode(id, latitude, longitude));
            outgoing.add(new ArrayList<>());
            return id;
        }

        public Builder addEdge(int from, int to, double distanceMeters, double travelTimeSeconds) {
            return addEdge(new Edge(from, to, distanceMeters, travelTimeSeconds));
        }

        /** Adds one directed edge. Parallel edges are allowed (two roads between the same junctions). */
        public Builder addEdge(Edge edge) {
            requireKnown(edge.from());
            requireKnown(edge.to());
            outgoing.get(edge.from()).add(edge);
            edgeCount++;
            return this;
        }

        /** Adds a two-way road as two directed edges. */
        public Builder addBidirectionalEdge(int a, int b, double distanceMeters, double travelTimeSeconds) {
            addEdge(a, b, distanceMeters, travelTimeSeconds);
            return addEdge(b, a, distanceMeters, travelTimeSeconds);
        }

        public int nodeCount() {
            return nodes.size();
        }

        public Graph build() {
            List<List<Edge>> frozen = new ArrayList<>(outgoing.size());
            for (List<Edge> edges : outgoing) {
                frozen.add(List.copyOf(edges));
            }
            return new Graph(List.copyOf(nodes), Collections.unmodifiableList(frozen), edgeCount);
        }

        private void requireKnown(int nodeId) {
            if (nodeId < 0 || nodeId >= nodes.size()) {
                throw new IllegalArgumentException(
                        "Edge references unknown node " + nodeId + " (builder has " + nodes.size() + " nodes)");
            }
        }
    }
}
