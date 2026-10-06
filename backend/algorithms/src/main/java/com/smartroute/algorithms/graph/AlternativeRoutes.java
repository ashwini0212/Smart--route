package com.smartroute.algorithms.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Up to k <em>reasonably different</em> routes between two nodes, using the penalty method.
 * <strong>[HEURISTIC]</strong>: it does not guarantee the k cheapest routes, nor the most diverse ones.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Find the optimal route with A*.</li>
 *   <li>Multiply the weight of every edge on routes found so far by {@code penalty} and search again.
 *       The search is pushed away from roads already used, so the next route tends to differ.</li>
 *   <li>Accept a candidate only if its <em>real</em> (unpenalized) cost is within {@code maxStretch} of the
 *       optimum and at most {@code maxOverlap} of its length is shared with any accepted route.</li>
 * </ol>
 *
 * <p>Why not Yen's k-shortest paths: on a road grid the 2nd to 10th shortest paths usually differ by one
 * block, which is useless as an "alternative". Yen is also O(k·V·(E + V log V)).
 *
 * <p>The same A* heuristic stays valid: penalties only increase weights, so a lower bound on the
 * unpenalized cost is still a lower bound (and still consistent).
 *
 * <p>Complexity: at most {@code maxAttempts} A* searches, O(maxAttempts · (V + E) log V) time.
 */
public final class AlternativeRoutes {

    public static final double DEFAULT_PENALTY = 1.4;
    public static final double DEFAULT_MAX_STRETCH = 1.4;
    public static final double DEFAULT_MAX_OVERLAP = 0.7;

    private AlternativeRoutes() {
    }

    public static List<Path> find(Graph graph, int source, int target, EdgeWeight weight, Heuristic heuristic, int k) {
        return find(graph, source, target, weight, heuristic, k, DEFAULT_PENALTY, DEFAULT_MAX_STRETCH, DEFAULT_MAX_OVERLAP);
    }

    /**
     * @return accepted routes, optimal first; empty if the target is unreachable; fewer than k if no more
     * acceptable alternatives were found
     */
    public static List<Path> find(Graph graph, int source, int target, EdgeWeight weight, Heuristic heuristic,
                                  int k, double penalty, double maxStretch, double maxOverlap) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        if (penalty <= 1 || maxStretch < 1 || maxOverlap < 0 || maxOverlap > 1) {
            throw new IllegalArgumentException("penalty must be > 1, maxStretch >= 1, maxOverlap in [0, 1]");
        }
        Path best = AStar.shortestPath(graph, source, target, weight, heuristic);
        List<Path> accepted = new ArrayList<>();
        if (!best.isFound()) {
            return accepted;
        }
        accepted.add(best);
        if (best.edges().isEmpty()) {
            return accepted; // source == target: there is no alternative to standing still
        }

        // How many times each edge has been penalized. Edge is a value record, so identical parallel edges
        // share an entry, which only makes the penalty slightly stronger.
        Map<Edge, Integer> penalties = new HashMap<>();
        best.edges().forEach(e -> penalties.merge(e, 1, Integer::sum));
        EdgeWeight penalized = edge -> weight.of(edge) * Math.pow(penalty, penalties.getOrDefault(edge, 0));

        int maxAttempts = 3 * k;
        for (int attempt = 0; attempt < maxAttempts && accepted.size() < k; attempt++) {
            Path candidate = AStar.shortestPath(graph, source, target, penalized, heuristic);
            candidate.edges().forEach(e -> penalties.merge(e, 1, Integer::sum));
            double realCost = cost(candidate, weight);
            if (realCost > maxStretch * best.cost()) {
                continue; // too long to be a useful alternative
            }
            Path real = new Path(candidate.nodeIds(), candidate.edges(), realCost, candidate.nodesSettled());
            if (accepted.stream().allMatch(other -> overlap(real, other, weight) <= maxOverlap)) {
                accepted.add(real);
            }
        }
        return accepted;
    }

    /** Share of {@code path}'s cost that runs over edges also used by {@code other}, in [0, 1]. */
    static double overlap(Path path, Path other, EdgeWeight weight) {
        double total = cost(path, weight);
        if (total == 0) {
            return 1;
        }
        Set<Edge> otherEdges = new HashSet<>(other.edges());
        double shared = 0;
        for (Edge edge : path.edges()) {
            if (otherEdges.contains(edge)) {
                shared += weight.of(edge);
            }
        }
        return shared / total;
    }

    private static double cost(Path path, EdgeWeight weight) {
        double sum = 0;
        for (Edge edge : path.edges()) {
            sum += weight.of(edge);
        }
        return sum;
    }
}
