package com.smartroute.algorithms.sequencing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HeldKarpTest {

    @ParameterizedTest(name = "{0} points match brute force")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void matchesBruteForceOnEveryInstance(int points) {
        Random random = new Random(points * 31L);
        for (int trial = 0; trial < 20; trial++) {
            CostMatrix matrix = SequencingTestSupport.randomEuclidean(random, points);
            for (boolean returnToStart : new boolean[] {false, true}) {
                Tour tour = HeldKarp.solve(matrix, 0, returnToStart);
                double optimum = SequencingTestSupport.bruteForceOptimum(matrix, 0, returnToStart);
                assertThat(tour.cost()).isCloseTo(optimum, within(1e-9));
                assertThat(tour.optimal()).isTrue();
                assertThat(matrix.tourCost(tour.order())).isCloseTo(tour.cost(), within(1e-9));
            }
        }
    }

    @Test
    void handlesAsymmetricCostsWhereDirectionMatters() {
        Random random = new Random(7);
        for (int trial = 0; trial < 20; trial++) {
            CostMatrix matrix = SequencingTestSupport.randomAsymmetric(random, 6);
            Tour tour = HeldKarp.solve(matrix, 0, true);
            assertThat(tour.cost()).isCloseTo(SequencingTestSupport.bruteForceOptimum(matrix, 0, true), within(1e-9));
        }
    }

    @Test
    void visitsEveryStopExactlyOnce() {
        Tour tour = HeldKarp.solve(SequencingTestSupport.randomEuclidean(new Random(1), 9), 0, true);
        int[] order = tour.order();
        assertThat(order).hasSize(10);
        assertThat(order[0]).isZero();
        assertThat(order[9]).isZero();
        assertThat(java.util.Arrays.stream(order, 1, 9).distinct().count()).isEqualTo(8);
    }

    @Test
    void startCanBeAnyPoint() {
        CostMatrix matrix = SequencingTestSupport.randomEuclidean(new Random(5), 7);
        for (int start = 0; start < 7; start++) {
            Tour tour = HeldKarp.solve(matrix, start, false);
            assertThat(tour.order()[0]).isEqualTo(start);
            assertThat(tour.cost())
                    .isCloseTo(SequencingTestSupport.bruteForceOptimum(matrix, start, false), within(1e-9));
        }
    }

    @Test
    void impossibleLegsAreSkippedRatherThanChosen() {
        // 0 → 1 → 2 is the only possible order: the direct hop 0 → 2 does not exist.
        double inf = Double.POSITIVE_INFINITY;
        CostMatrix matrix = CostMatrix.of(new double[][] {
                {0, 1, inf},
                {1, 0, 1},
                {inf, 1, 0}});
        assertThat(matrix.isComplete()).isFalse();
        Tour tour = HeldKarp.solve(matrix, 0, false);
        assertThat(tour.order()).containsExactly(0, 1, 2);
        assertThat(tour.cost()).isEqualTo(2);
    }

    @Test
    void oneAndZeroStopCasesAreHandled() {
        CostMatrix single = CostMatrix.of(new double[][] {{0}});
        assertThat(HeldKarp.solve(single, 0, false).order()).containsExactly(0);
        assertThat(HeldKarp.solve(single, 0, true).order()).containsExactly(0, 0);
        CostMatrix two = CostMatrix.of(new double[][] {{0, 3}, {4, 0}});
        Tour tour = HeldKarp.solve(two, 0, true);
        assertThat(tour.order()).containsExactly(0, 1, 0);
        assertThat(tour.cost()).isEqualTo(7);
    }

    @Test
    void refusesInputsTooLargeForTheTable() {
        CostMatrix big = SequencingTestSupport.randomEuclidean(new Random(2), HeldKarp.MAX_STOPS + 2);
        assertThatThrownBy(() -> HeldKarp.solve(big, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited to " + HeldKarp.MAX_STOPS);
    }

    @Test
    void stateCountGrowsAsTwoToTheN() {
        // The point of the DP: 2^n subsets, not n! orders. 10 stops = 1023 reachable sets, 10! = 3.6 million.
        long steps = HeldKarp.solve(SequencingTestSupport.randomEuclidean(new Random(3), 11), 0, false).steps();
        assertThat(steps).isEqualTo(10 * (1L << 9));  // every stop appears as the last one of half the sets
    }
}
