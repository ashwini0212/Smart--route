package com.smartroute.algorithms.sequencing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TwoOptTest {

    @Test
    void removesACrossing() {
        // Four points of a square in the order 0, 2, 1, 3 cross in the middle; the square itself does not.
        double[][] xy = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        double[][] cost = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                cost[i][j] = Math.hypot(xy[i][0] - xy[j][0], xy[i][1] - xy[j][1]);
            }
        }
        CostMatrix matrix = CostMatrix.of(cost);
        Tour crossing = new Tour(new int[] {0, 2, 1, 3, 0}, 0, "handmade", false, 0);
        Tour improved = TwoOpt.improve(matrix, crossing, true);
        assertThat(improved.cost()).isLessThan(matrix.tourCost(crossing.order()));
        assertThat(improved.cost()).isCloseTo(4.0, org.assertj.core.api.Assertions.within(1e-9)); // the square
        assertThat(improved.steps()).isPositive();
    }

    @Test
    void neverMakesATourWorseAndKeepsEveryStop() {
        Random random = new Random(11);
        for (int trial = 0; trial < 50; trial++) {
            int points = 4 + random.nextInt(12);
            CostMatrix matrix = SequencingTestSupport.randomEuclidean(random, points);
            for (boolean returnToStart : new boolean[] {false, true}) {
                Tour start = NearestNeighbourTour.of(matrix, 0, returnToStart);
                Tour improved = TwoOpt.improve(matrix, start, returnToStart);
                assertThat(improved.cost()).isLessThanOrEqualTo(start.cost() + 1e-9);
                int[] order = improved.order();
                assertThat(order[0]).isZero();
                if (returnToStart) {
                    assertThat(order[order.length - 1]).isZero();
                }
                assertThat(Arrays.stream(order, 1, returnToStart ? order.length - 1 : order.length).distinct().count())
                        .isEqualTo(points - 1);
            }
        }
    }

    @Test
    void theStartStaysWhereTheDriverIs() {
        CostMatrix matrix = SequencingTestSupport.randomEuclidean(new Random(4), 8);
        for (int start = 0; start < 8; start++) {
            Tour tour = TwoOpt.improve(matrix, NearestNeighbourTour.of(matrix, start, false), false);
            assertThat(tour.order()[0]).isEqualTo(start);
        }
    }

    @Test
    void resultIsTwoOptimalNoSingleReversalImprovesIt() {
        CostMatrix matrix = SequencingTestSupport.randomEuclidean(new Random(9), 10);
        Tour tour = TwoOpt.improve(matrix, NearestNeighbourTour.of(matrix, 0, true), true);
        int[] order = tour.order();
        for (int i = 1; i < order.length - 2; i++) {
            for (int j = i + 1; j < order.length - 1; j++) {
                int[] candidate = order.clone();
                for (int a = i, b = j; a < b; a++, b--) {
                    int tmp = candidate[a];
                    candidate[a] = candidate[b];
                    candidate[b] = tmp;
                }
                assertThat(matrix.tourCost(candidate)).isGreaterThanOrEqualTo(tour.cost() - 1e-9);
            }
        }
    }

    @Test
    void handlesAsymmetricCostsCorrectly() {
        // Reversing a segment changes the direction of every leg inside it, so a naive symmetric delta
        // would mis-evaluate moves here. The check is simply that the reported cost is the real one.
        Random random = new Random(21);
        for (int trial = 0; trial < 30; trial++) {
            CostMatrix matrix = SequencingTestSupport.randomAsymmetric(random, 9);
            Tour tour = TwoOpt.improve(matrix, NearestNeighbourTour.of(matrix, 0, true), true);
            assertThat(tour.cost()).isCloseTo(matrix.tourCost(tour.order()),
                    org.assertj.core.api.Assertions.within(1e-9));
        }
    }

    @Test
    void maxPassesBoundsTheWork() {
        CostMatrix matrix = SequencingTestSupport.randomEuclidean(new Random(13), 30);
        Tour start = NearestNeighbourTour.of(matrix, 0, true);
        Tour onePass = TwoOpt.improve(matrix, start, true, 1);
        Tour unlimited = TwoOpt.improve(matrix, start, true);
        assertThat(onePass.cost()).isLessThanOrEqualTo(start.cost());
        assertThat(unlimited.cost()).isLessThanOrEqualTo(onePass.cost() + 1e-9);
    }
}
