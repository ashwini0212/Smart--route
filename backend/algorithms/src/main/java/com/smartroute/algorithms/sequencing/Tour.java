package com.smartroute.algorithms.sequencing;

import java.util.Arrays;

/**
 * A visiting order and its cost.
 *
 * @param order     point indices in visiting order, starting with the start point; when the tour returns
 *                  to the start, the start index appears again at the end
 * @param cost      total cost of the legs in {@code order}
 * @param algorithm which algorithm produced it
 * @param optimal   true only when the result is proven optimal for the given matrix (Held-Karp or a
 *                  case small enough to be optimal by construction)
 * @param steps     work done: improving moves applied (2-opt) or states expanded (Held-Karp)
 */
public record Tour(int[] order, double cost, String algorithm, boolean optimal, long steps) {

    public Tour {
        order = order.clone();
    }

    @Override
    public int[] order() {
        return order.clone();
    }

    @Override
    public String toString() {
        return "Tour" + Arrays.toString(order) + " cost=" + cost + " by " + algorithm + (optimal ? " (optimal)" : "");
    }
}
