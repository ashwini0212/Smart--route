package com.smartroute.algorithms.sequencing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostMatrixTest {

    @Test
    void copiesItsInputSoLaterChangesCannotAffectIt() {
        double[][] raw = {{0, 5}, {5, 0}};
        CostMatrix matrix = CostMatrix.of(raw);
        raw[0][1] = 99;
        assertThat(matrix.cost(0, 1)).isEqualTo(5);
    }

    @Test
    void rejectsNonSquareNegativeAndEmptyInput() {
        assertThatThrownBy(() -> CostMatrix.of(new double[][] {{0, 1}})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostMatrix.of(new double[][] {{0, -1}, {1, 0}}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostMatrix.of(new double[0][0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tourCostSumsTheLegsInOrder() {
        CostMatrix matrix = CostMatrix.of(new double[][] {{0, 1, 4}, {2, 0, 1}, {5, 3, 0}});
        assertThat(matrix.tourCost(new int[] {0, 1, 2})).isEqualTo(2);     // 1 + 1
        assertThat(matrix.tourCost(new int[] {0, 2, 1, 0})).isEqualTo(9);  // 4 + 3 + 2, direction matters
        assertThat(matrix.tourCost(new int[] {0})).isZero();
    }

    @Test
    void completenessDetectsMissingConnections() {
        assertThat(CostMatrix.of(new double[][] {{0, 1}, {1, 0}}).isComplete()).isTrue();
        assertThat(CostMatrix.of(new double[][] {{0, Double.POSITIVE_INFINITY}, {1, 0}}).isComplete()).isFalse();
    }
}
