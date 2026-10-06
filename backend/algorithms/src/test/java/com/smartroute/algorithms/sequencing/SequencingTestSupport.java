package com.smartroute.algorithms.sequencing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared helpers: random instances and a brute-force optimum to compare against. */
final class SequencingTestSupport {

    private SequencingTestSupport() {
    }

    /** Euclidean distances between random points in a unit square (symmetric, complete). */
    static CostMatrix randomEuclidean(Random random, int points) {
        double[][] xy = new double[points][2];
        for (int i = 0; i < points; i++) {
            xy[i][0] = random.nextDouble();
            xy[i][1] = random.nextDouble();
        }
        double[][] cost = new double[points][points];
        for (int i = 0; i < points; i++) {
            for (int j = 0; j < points; j++) {
                cost[i][j] = Math.hypot(xy[i][0] - xy[j][0], xy[i][1] - xy[j][1]);
            }
        }
        return CostMatrix.of(cost);
    }

    /** Random costs that differ by direction, as one-way streets do. */
    static CostMatrix randomAsymmetric(Random random, int points) {
        double[][] cost = new double[points][points];
        for (int i = 0; i < points; i++) {
            for (int j = 0; j < points; j++) {
                cost[i][j] = i == j ? 0 : 1 + random.nextDouble() * 10;
            }
        }
        return CostMatrix.of(cost);
    }

    /** The true optimum by trying every order: O(n!), only for tiny instances. */
    static double bruteForceOptimum(CostMatrix matrix, int start, boolean returnToStart) {
        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < matrix.size(); i++) {
            if (i != start) {
                rest.add(i);
            }
        }
        return permute(matrix, start, rest, new ArrayList<>(), returnToStart, Double.POSITIVE_INFINITY);
    }

    private static double permute(CostMatrix matrix, int start, List<Integer> rest, List<Integer> prefix,
                                  boolean returnToStart, double best) {
        if (rest.isEmpty()) {
            int[] order = new int[prefix.size() + (returnToStart ? 2 : 1)];
            order[0] = start;
            for (int i = 0; i < prefix.size(); i++) {
                order[i + 1] = prefix.get(i);
            }
            if (returnToStart) {
                order[order.length - 1] = start;
            }
            return Math.min(best, matrix.tourCost(order));
        }
        for (int i = 0; i < rest.size(); i++) {
            List<Integer> remaining = new ArrayList<>(rest);
            Integer next = remaining.remove(i);
            List<Integer> extended = new ArrayList<>(prefix);
            extended.add(next);
            best = Math.min(best, permute(matrix, start, remaining, extended, returnToStart, best));
        }
        return best;
    }
}
