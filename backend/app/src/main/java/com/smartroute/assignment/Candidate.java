package com.smartroute.assignment;

import com.smartroute.fleet.VehicleType;

import java.math.BigDecimal;

/**
 * A driver who passed every hard rule, with the score breakdown that ranked them.
 *
 * @param etaSeconds          road-network travel time to the pickup, with current traffic (Dijkstra: optimal)
 * @param straightLineMeters  distance used only for the pre-filter
 */
public record Candidate(int rank, long driverId, String driverCode, VehicleType vehicleType, double etaSeconds,
                        double straightLineMeters, int activeDeliveries, BigDecimal remainingKg,
                        BigDecimal remainingM3, double score, double etaScore, double workloadScore,
                        double capacityScore) {

    Candidate withRank(int newRank) {
        return new Candidate(newRank, driverId, driverCode, vehicleType, etaSeconds, straightLineMeters,
                activeDeliveries, remainingKg, remainingM3, score, etaScore, workloadScore, capacityScore);
    }
}
