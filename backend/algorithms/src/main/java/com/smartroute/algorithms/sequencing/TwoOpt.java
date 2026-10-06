package com.smartroute.algorithms.sequencing;

/**
 * Local search: repeatedly reverse a segment of the tour if that makes it shorter.
 *
 * <pre>
 * ... a → b ... c → d ...        becomes       ... a → c ... b → d ...
 *     (the segment b..c is reversed)
 * </pre>
 *
 * <p>This removes the crossings a nearest-neighbour tour leaves behind. One pass tries all O(n²) pairs;
 * passes repeat until no move improves, so the cost is O(n² · passes) with the tour length decreasing
 * strictly each time. The result is <b>2-optimal</b>, meaning no single such reversal improves it; that is
 * not the same as optimal, because improving it might need three or more edges to change at once.
 *
 * <p>On an asymmetric matrix (one-way streets) reversing a segment changes the direction of travel inside
 * it, so every leg of the reversed part is re-summed rather than using the usual symmetric shortcut.
 * {@code maxPasses} bounds the work for large inputs.
 */
public final class TwoOpt {

    private TwoOpt() {
    }

    public static Tour improve(CostMatrix matrix, Tour start, boolean returnToStart) {
        return improve(matrix, start, returnToStart, Integer.MAX_VALUE);
    }

    public static Tour improve(CostMatrix matrix, Tour start, boolean returnToStart, int maxPasses) {
        int[] order = start.order();
        double cost = matrix.tourCost(order);
        if (Double.isInfinite(cost)) {
            // A tour with an impossible leg cannot be compared meaningfully; leave it as it is.
            return new Tour(order, cost, "nearest neighbour, 2-opt skipped (unreachable stop)", false, 0);
        }
        // The first point is fixed (it is where the driver stands). With a return leg the last point is the
        // start again and is fixed too; without one the final stop may move.
        int last = returnToStart ? order.length - 2 : order.length - 1;
        long moves = 0;
        boolean improved = true;
        int passes = 0;
        while (improved && passes < maxPasses) {
            improved = false;
            passes++;
            for (int i = 1; i <= last - 1; i++) {
                for (int j = i + 1; j <= last; j++) {
                    double delta = reversalDelta(matrix, order, i, j, returnToStart);
                    if (delta < -1e-9) {
                        reverse(order, i, j);
                        cost += delta;
                        moves++;
                        improved = true;
                    }
                }
            }
        }
        return new Tour(order, matrix.tourCost(order), "nearest neighbour + 2-opt [HEURISTIC]", false, moves);
    }

    /** Cost change if {@code order[i..j]} were reversed; {@code +∞} when the result would be impossible. */
    private static double reversalDelta(CostMatrix matrix, int[] order, int i, int j, boolean returnToStart) {
        int beforeIndex = i - 1;
        int afterIndex = j + 1;
        boolean hasAfter = afterIndex < order.length && (returnToStart || afterIndex <= order.length - 1);
        double before = matrix.cost(order[beforeIndex], order[i]);
        double after = hasAfter ? matrix.cost(order[j], order[afterIndex]) : 0;
        double inside = 0;
        for (int k = i; k < j; k++) {
            inside += matrix.cost(order[k], order[k + 1]);
        }
        double newBefore = matrix.cost(order[beforeIndex], order[j]);
        double newAfter = hasAfter ? matrix.cost(order[i], order[afterIndex]) : 0;
        double newInside = 0;
        for (int k = j; k > i; k--) {
            newInside += matrix.cost(order[k], order[k - 1]);
        }
        double now = before + inside + after;
        double next = newBefore + newInside + newAfter;
        if (Double.isInfinite(next)) {
            return Double.POSITIVE_INFINITY;
        }
        return next - now;
    }

    private static void reverse(int[] order, int from, int to) {
        while (from < to) {
            int tmp = order[from];
            order[from] = order[to];
            order[to] = tmp;
            from++;
            to--;
        }
    }
}
