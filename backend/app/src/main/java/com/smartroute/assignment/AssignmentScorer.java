package com.smartroute.assignment;

import com.smartroute.order.OrderPriority;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Scores a driver for an order. <b>[HEURISTIC]</b>: a weighted sum of three terms in [0, 1], not an optimum
 * of any global objective. Higher is better.
 *
 * <ul>
 *   <li><b>ETA</b> = 1 − min(eta, cap) / cap. Faster pickup is better; beyond the cap every driver is
 *       equally "far". Its weight is multiplied by a priority factor (LOW 0.75, NORMAL 1, HIGH 1.5,
 *       URGENT 2), so urgent orders care more about speed than about spreading work.</li>
 *   <li><b>Workload</b> = 1 − active / maxActive. Spreads orders instead of piling them on the nearest driver.</li>
 *   <li><b>Capacity fit</b> = the larger of (order weight / remaining kg) and (order volume / remaining m³).
 *       Best-fit bin packing: a 500 kg order prefers a van it nearly fills over an empty truck, which keeps
 *       trucks free for freight that only they can carry.</li>
 * </ul>
 *
 * <p>Total = Σ wᵢ·termᵢ / Σ wᵢ, so it stays in [0, 1] whatever the configured weights. O(1) per driver.
 */
final class AssignmentScorer {

    private AssignmentScorer() {
    }

    record Score(double total, double eta, double workload, double capacity) {
    }

    static double priorityFactor(OrderPriority priority) {
        return switch (priority) {
            case LOW -> 0.75;
            case NORMAL -> 1.0;
            case HIGH -> 1.5;
            case URGENT -> 2.0;
        };
    }

    static Score score(double etaSeconds, int activeDeliveries, double capacityFit, OrderPriority priority,
                       AssignmentSettings settings) {
        double eta = 1.0 - Math.min(etaSeconds, settings.etaCapSeconds()) / settings.etaCapSeconds();
        double workload = 1.0 - Math.min(1.0, (double) activeDeliveries / settings.maxActiveDeliveries());
        double capacity = Math.clamp(capacityFit, 0.0, 1.0);
        double etaWeight = settings.etaWeight() * priorityFactor(priority);
        double totalWeight = etaWeight + settings.workloadWeight() + settings.capacityWeight();
        double total = (etaWeight * eta + settings.workloadWeight() * workload + settings.capacityWeight() * capacity)
                / totalWeight;
        return new Score(total, eta, workload, capacity);
    }

    /** How full the vehicle's remaining space would be with this order: 1 = exactly fills it. */
    static double capacityFit(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal remainingKg, BigDecimal remainingM3) {
        return Math.max(ratio(weightKg, remainingKg), ratio(volumeM3, remainingM3));
    }

    private static double ratio(BigDecimal part, BigDecimal whole) {
        if (whole.signum() <= 0) {
            return 1.0;
        }
        return part.divide(whole, MathContext.DECIMAL64).doubleValue();
    }
}
