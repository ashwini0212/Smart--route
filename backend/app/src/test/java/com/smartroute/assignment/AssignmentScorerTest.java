package com.smartroute.assignment;

import com.smartroute.order.OrderPriority;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AssignmentScorerTest {

    private static final AssignmentSettings DEFAULTS =
            new AssignmentSettings(0.6, 0.25, 0.15, 1800, 5000, 50, 8, Instant.EPOCH);

    @Test
    void termsAreInZeroToOne() {
        AssignmentScorer.Score best = AssignmentScorer.score(0, 0, 1.0, OrderPriority.NORMAL, DEFAULTS);
        assertThat(best.total()).isCloseTo(1.0, within(1e-12));
        AssignmentScorer.Score worst = AssignmentScorer.score(10_000, 8, 0.0, OrderPriority.NORMAL, DEFAULTS);
        assertThat(worst.total()).isCloseTo(0.0, within(1e-12));
    }

    @Test
    void etaBeyondTheCapScoresZeroOnTheEtaTerm() {
        assertThat(AssignmentScorer.score(900, 0, 0, OrderPriority.NORMAL, DEFAULTS).eta()).isCloseTo(0.5, within(1e-12));
        assertThat(AssignmentScorer.score(1800, 0, 0, OrderPriority.NORMAL, DEFAULTS).eta()).isZero();
        assertThat(AssignmentScorer.score(3600, 0, 0, OrderPriority.NORMAL, DEFAULTS).eta()).isZero();
    }

    @Test
    void weightedSumIsNormalized() {
        // NORMAL: (0.6 * 0.5 + 0.25 * 0.75 + 0.15 * 0.2) / 1.0
        AssignmentScorer.Score s = AssignmentScorer.score(900, 2, 0.2, OrderPriority.NORMAL, DEFAULTS);
        assertThat(s.total()).isCloseTo(0.3 + 0.1875 + 0.03, within(1e-12));
        // Doubling every weight changes nothing.
        AssignmentSettings doubled = new AssignmentSettings(1.2, 0.5, 0.3, 1800, 5000, 50, 8, Instant.EPOCH);
        assertThat(AssignmentScorer.score(900, 2, 0.2, OrderPriority.NORMAL, doubled).total())
                .isCloseTo(s.total(), within(1e-12));
    }

    @Test
    void urgentOrdersWeighEtaMoreThanWorkload() {
        // Driver A: near (5 min) but busy (6 of 8). Driver B: farther (15 min) and idle.
        double nearBusyLow = AssignmentScorer.score(300, 6, 0.1, OrderPriority.LOW, DEFAULTS).total();
        double farIdleLow = AssignmentScorer.score(900, 0, 0.1, OrderPriority.LOW, DEFAULTS).total();
        double nearBusyUrgent = AssignmentScorer.score(300, 6, 0.1, OrderPriority.URGENT, DEFAULTS).total();
        double farIdleUrgent = AssignmentScorer.score(900, 0, 0.1, OrderPriority.URGENT, DEFAULTS).total();
        assertThat(farIdleLow).isGreaterThan(nearBusyLow);
        assertThat(nearBusyUrgent).isGreaterThan(farIdleUrgent);
    }

    @Test
    void etaOnlyWeightsReduceToNearestDriver() {
        AssignmentSettings etaOnly = new AssignmentSettings(1, 0, 0, 1800, 5000, 50, 8, Instant.EPOCH);
        double near = AssignmentScorer.score(300, 7, 0.0, OrderPriority.NORMAL, etaOnly).total();
        double far = AssignmentScorer.score(301, 0, 1.0, OrderPriority.NORMAL, etaOnly).total();
        assertThat(near).isGreaterThan(far);
    }

    @Test
    void capacityFitIsTheTighterOfWeightAndVolume() {
        assertThat(AssignmentScorer.capacityFit(new BigDecimal("500"), new BigDecimal("1"),
                new BigDecimal("600"), new BigDecimal("4"))).isCloseTo(500.0 / 600, within(1e-12));
        assertThat(AssignmentScorer.capacityFit(new BigDecimal("10"), new BigDecimal("3"),
                new BigDecimal("600"), new BigDecimal("4"))).isCloseTo(0.75, within(1e-12));
        // A van that the order nearly fills beats an empty truck.
        double van = AssignmentScorer.capacityFit(new BigDecimal("500"), new BigDecimal("2"),
                new BigDecimal("600"), new BigDecimal("4"));
        double truck = AssignmentScorer.capacityFit(new BigDecimal("500"), new BigDecimal("2"),
                new BigDecimal("3000"), new BigDecimal("18"));
        assertThat(van).isGreaterThan(truck);
    }
}
