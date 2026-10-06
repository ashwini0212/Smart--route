package com.smartroute.algorithms.sequencing;

/**
 * Pairwise travel costs between points, {@code cost[i][j]} being the cost of going from i to j.
 *
 * <p>Asymmetric on purpose: on one-way streets the way back is not the way there, so {@code cost[i][j]}
 * may differ from {@code cost[j][i]}. {@code +∞} means "no road leads from i to j".
 */
public final class CostMatrix {

    private final double[][] cost;

    private CostMatrix(double[][] cost) {
        this.cost = cost;
    }

    /** Copies the given rows; every row must have as many entries as there are rows. */
    public static CostMatrix of(double[][] cost) {
        if (cost.length == 0) {
            throw new IllegalArgumentException("A cost matrix needs at least one point");
        }
        double[][] copy = new double[cost.length][];
        for (int i = 0; i < cost.length; i++) {
            if (cost[i].length != cost.length) {
                throw new IllegalArgumentException("Row " + i + " has " + cost[i].length + " entries, expected "
                        + cost.length);
            }
            copy[i] = cost[i].clone();
            for (int j = 0; j < copy[i].length; j++) {
                if (copy[i][j] < 0 || Double.isNaN(copy[i][j])) {
                    throw new IllegalArgumentException("Cost from " + i + " to " + j + " must be >= 0, got " + copy[i][j]);
                }
            }
        }
        return new CostMatrix(copy);
    }

    public int size() {
        return cost.length;
    }

    public double cost(int from, int to) {
        return cost[from][to];
    }

    /** True when every pair is connected in both directions (so any visiting order is possible). */
    public boolean isComplete() {
        for (int i = 0; i < cost.length; i++) {
            for (int j = 0; j < cost.length; j++) {
                if (i != j && Double.isInfinite(cost[i][j])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Cost of visiting {@code order} in sequence (no wrap-around); {@code +∞} if any leg is impossible. */
    public double tourCost(int[] order) {
        double total = 0;
        for (int i = 0; i + 1 < order.length; i++) {
            total += cost[order[i]][order[i + 1]];
        }
        return total;
    }
}
