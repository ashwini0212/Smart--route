package com.smartroute.algorithms.sequencing;

/**
 * Greedy construction: from the current point, go to the nearest point not yet visited.
 *
 * <p>Time O(n²), space O(n). It is <b>not</b> optimal and can be arbitrarily bad in theory; in practice
 * it lands within a few tens of percent of the optimum and gives 2-opt a good starting tour. The last
 * stop is often a long jump back across the area, which is exactly what 2-opt repairs.
 *
 * <p>If a leg is impossible ({@code +∞}), the nearest reachable point is chosen instead; a tour is
 * returned with an infinite cost only when some point cannot be reached at all.
 */
public final class NearestNeighbourTour {

    private NearestNeighbourTour() {
    }

    public static Tour of(CostMatrix matrix, int start, boolean returnToStart) {
        int n = matrix.size();
        if (start < 0 || start >= n) {
            throw new IllegalArgumentException("start must be a point of the matrix");
        }
        boolean[] visited = new boolean[n];
        int[] order = new int[returnToStart ? n + 1 : n];
        order[0] = start;
        visited[start] = true;
        int current = start;
        for (int step = 1; step < n; step++) {
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int next = 0; next < n; next++) {
                // Ties go to the lower index, so the result does not depend on iteration luck.
                if (!visited[next] && matrix.cost(current, next) < bestCost) {
                    bestCost = matrix.cost(current, next);
                    best = next;
                }
            }
            if (best < 0) {
                // Everything left is unreachable from here: append in index order, cost stays infinite.
                for (int next = 0; next < n; next++) {
                    if (!visited[next]) {
                        best = next;
                        break;
                    }
                }
            }
            visited[best] = true;
            order[step] = best;
            current = best;
        }
        if (returnToStart) {
            order[n] = start;
        }
        return new Tour(order, matrix.tourCost(order), "nearest neighbour [HEURISTIC]", n <= 2, n - 1);
    }
}
