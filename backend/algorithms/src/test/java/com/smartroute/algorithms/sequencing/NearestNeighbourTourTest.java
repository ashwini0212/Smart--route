package com.smartroute.algorithms.sequencing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class NearestNeighbourTourTest {

    @Test
    void alwaysGoesToTheNearestUnvisitedPoint() {
        // A line: 0 at x=0, then stops at 1, 3, 7. Greedy walks right along it.
        double[] x = {0, 1, 3, 7};
        double[][] cost = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                cost[i][j] = Math.abs(x[i] - x[j]);
            }
        }
        Tour tour = NearestNeighbourTour.of(CostMatrix.of(cost), 0, false);
        assertThat(tour.order()).containsExactly(0, 1, 2, 3);
        assertThat(tour.cost()).isCloseTo(7, within(1e-9));
        assertThat(tour.optimal()).isFalse();
        assertThat(tour.algorithm()).contains("[HEURISTIC]");
    }

    @Test
    void canBeWorseThanOptimalWhichIsWhyTwoOptExists() {
        // Classic greedy trap: the cheap first hop forces a long jump at the end.
        double[] x = {0, -2, 1, 4};
        double[][] cost = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                cost[i][j] = Math.abs(x[i] - x[j]);
            }
        }
        CostMatrix matrix = CostMatrix.of(cost);
        Tour greedy = NearestNeighbourTour.of(matrix, 0, false);
        double optimum = SequencingTestSupport.bruteForceOptimum(matrix, 0, false);
        assertThat(greedy.cost()).isGreaterThan(optimum);
        assertThat(TwoOpt.improve(matrix, greedy, false).cost()).isCloseTo(optimum, within(1e-9));
    }

    @Test
    void visitsEveryPointOnceAndReturnsWhenAsked() {
        Random random = new Random(17);
        CostMatrix matrix = SequencingTestSupport.randomEuclidean(random, 12);
        Tour open = NearestNeighbourTour.of(matrix, 3, false);
        assertThat(open.order()).hasSize(12).startsWith(3);
        assertThat(Arrays.stream(open.order()).distinct().count()).isEqualTo(12);
        Tour closed = NearestNeighbourTour.of(matrix, 3, true);
        assertThat(closed.order()).hasSize(13).startsWith(3).endsWith(3);
        assertThat(closed.cost()).isGreaterThan(open.cost());
    }

    @Test
    void unreachablePointsLeaveAnInfiniteCostRatherThanADroppedStop() {
        double inf = Double.POSITIVE_INFINITY;
        CostMatrix matrix = CostMatrix.of(new double[][] {
                {0, 1, inf},
                {1, 0, inf},
                {inf, inf, 0}});
        Tour tour = NearestNeighbourTour.of(matrix, 0, false);
        assertThat(tour.order()).containsExactly(0, 1, 2);
        assertThat(tour.cost()).isInfinite();
        // 2-opt leaves such a tour alone and says so.
        assertThat(TwoOpt.improve(matrix, tour, false).algorithm()).contains("unreachable");
    }

    @Test
    void rejectsAStartOutsideTheMatrix() {
        CostMatrix matrix = CostMatrix.of(new double[][] {{0, 1}, {1, 0}});
        assertThatThrownBy(() -> NearestNeighbourTour.of(matrix, 2, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
