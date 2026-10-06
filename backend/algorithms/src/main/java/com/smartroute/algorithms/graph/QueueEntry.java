package com.smartroute.algorithms.graph;

import java.util.Comparator;

/**
 * Priority-queue element for Dijkstra/A*: a node and the priority it was pushed with.
 *
 * <p>Ties are broken by node id so that, for the same input, the same path is always returned
 * (important for tests, caching and reproducible benchmarks).
 */
record QueueEntry(int node, double priority) {

    static final Comparator<QueueEntry> ORDER =
            Comparator.comparingDouble(QueueEntry::priority).thenComparingInt(QueueEntry::node);
}
