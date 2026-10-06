package com.smartroute.algorithms.sequencing;

/**
 * Chooses how to order stops: exact when the input is small enough, otherwise a heuristic.
 *
 * <p>The threshold is a deliberate trade: Held-Karp is exact but its cost grows as n²·2ⁿ, so somewhere it
 * stops being worth the wait. Nearest neighbour plus 2-opt costs O(n²) per pass and has no optimality
 * guarantee. The caller always learns which one ran, and whether the answer is optimal.
 */
public final class StopSequencer {

    /**
     * Up to this many stops (besides the start), the exact algorithm is used. 12 because that is where the
     * measured exact run time is still about 3 ms on a 4-vCPU VM; it roughly doubles per extra stop
     * (13 ms at 14 stops), while nearest neighbour + 2-opt stays at ~0.03 ms and lands within a few percent
     * of the optimum. See docs/benchmarks/phase-8-sequencing.txt.
     */
    public static final int DEFAULT_EXACT_LIMIT = 12;

    private StopSequencer() {
    }

    public static Tour sequence(CostMatrix matrix, int start, boolean returnToStart, int exactLimit) {
        int stops = matrix.size() - 1;
        if (stops <= Math.min(exactLimit, HeldKarp.MAX_STOPS)) {
            return HeldKarp.solve(matrix, start, returnToStart);
        }
        return TwoOpt.improve(matrix, NearestNeighbourTour.of(matrix, start, returnToStart), returnToStart);
    }

    public static Tour sequence(CostMatrix matrix, int start, boolean returnToStart) {
        return sequence(matrix, start, returnToStart, DEFAULT_EXACT_LIMIT);
    }
}
