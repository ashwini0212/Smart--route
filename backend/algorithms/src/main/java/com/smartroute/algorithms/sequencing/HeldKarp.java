package com.smartroute.algorithms.sequencing;

import java.util.Arrays;

/**
 * Exact shortest visiting order by dynamic programming over subsets (Held-Karp, 1962).
 *
 * <p>State: {@code best[S][i]} = cheapest way to start at the start point, visit exactly the stops in set
 * {@code S}, and stand at stop {@code i}. Each state is extended by one unvisited stop:
 *
 * <pre>
 * best[S ∪ {j}][j] = min over i ∈ S of best[S][i] + cost(i, j)
 * </pre>
 *
 * <p>The set is a bitmask, so the table is an array. Time O(n²·2ⁿ), space O(n·2ⁿ): exact but exponential,
 * which is why it is used only for small inputs. The travelling-salesman problem is NP-hard, so no
 * polynomial algorithm is known; brute force over all orders would be O(n!) (12 stops: 479 million orders
 * versus 590 thousand states here).
 *
 * <p>Works on asymmetric matrices, and on matrices with impossible legs (those states stay {@code +∞}).
 */
public final class HeldKarp {

    /** 16 stops: 1,048,576 states × 16 → about 134 MB of doubles. Beyond this the memory is the problem. */
    public static final int MAX_STOPS = 16;

    private HeldKarp() {
    }

    /**
     * @param start         index of the point the tour starts from
     * @param returnToStart whether the cost of coming back to the start counts
     * @throws IllegalArgumentException if there are more than {@link #MAX_STOPS} stops besides the start
     */
    public static Tour solve(CostMatrix matrix, int start, boolean returnToStart) {
        int n = matrix.size();
        int stops = n - 1;
        if (stops > MAX_STOPS) {
            throw new IllegalArgumentException("Held-Karp is limited to " + MAX_STOPS + " stops, got " + stops);
        }
        if (stops <= 0) {
            int[] order = returnToStart ? new int[] {start, start} : new int[] {start};
            return new Tour(order, matrix.tourCost(order), "Held-Karp exact", true, 0);
        }
        // Map stop indices 0..stops-1 to point indices, skipping the start.
        int[] point = new int[stops];
        for (int i = 0, k = 0; i < n; i++) {
            if (i != start) {
                point[k++] = i;
            }
        }
        int sets = 1 << stops;
        double[][] best = new double[sets][stops];
        int[][] from = new int[sets][stops];
        for (double[] row : best) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (int i = 0; i < stops; i++) {
            best[1 << i][i] = matrix.cost(start, point[i]);
            from[1 << i][i] = -1;
        }
        long expanded = 0;
        for (int set = 1; set < sets; set++) {
            for (int i = 0; i < stops; i++) {
                double current = best[set][i];
                if ((set & (1 << i)) == 0 || Double.isInfinite(current)) {
                    continue;
                }
                expanded++;
                for (int j = 0; j < stops; j++) {
                    if ((set & (1 << j)) != 0) {
                        continue;
                    }
                    double candidate = current + matrix.cost(point[i], point[j]);
                    int next = set | (1 << j);
                    if (candidate < best[next][j]) {
                        best[next][j] = candidate;
                        from[next][j] = i;
                    }
                }
            }
        }
        int full = sets - 1;
        int lastStop = -1;
        double total = Double.POSITIVE_INFINITY;
        for (int i = 0; i < stops; i++) {
            double candidate = best[full][i] + (returnToStart ? matrix.cost(point[i], start) : 0);
            if (candidate < total) {
                total = candidate;
                lastStop = i;
            }
        }
        if (lastStop < 0) {
            // Every order is impossible; fall back to index order so the caller still gets a tour.
            int[] order = new int[returnToStart ? n + 1 : n];
            order[0] = start;
            System.arraycopy(point, 0, order, 1, stops);
            if (returnToStart) {
                order[n] = start;
            }
            return new Tour(order, Double.POSITIVE_INFINITY, "Held-Karp exact (no possible order)", true, expanded);
        }
        int[] reverse = new int[stops];
        int set = full;
        int stop = lastStop;
        for (int k = stops - 1; k >= 0; k--) {
            reverse[k] = point[stop];
            int previous = from[set][stop];
            set &= ~(1 << stop);
            stop = previous;
        }
        int[] order = new int[returnToStart ? n + 1 : n];
        order[0] = start;
        System.arraycopy(reverse, 0, order, 1, stops);
        if (returnToStart) {
            order[n] = start;
        }
        return new Tour(order, total, "Held-Karp exact", true, expanded);
    }
}
