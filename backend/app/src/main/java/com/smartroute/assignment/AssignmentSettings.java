package com.smartroute.assignment;

import java.time.Instant;

/**
 * The scoring weights and limits in force (an immutable copy of the {@code assignment_config} row).
 *
 * @param etaCapSeconds        an ETA at or above this scores 0 on the ETA term
 * @param searchRadiusMeters   straight-line pre-filter around the pickup
 * @param maxCandidates        at most this many nearest eligible drivers get a road-network ETA
 * @param maxActiveDeliveries  a driver with this many active deliveries takes no more
 */
public record AssignmentSettings(double etaWeight, double workloadWeight, double capacityWeight, int etaCapSeconds,
                                 int searchRadiusMeters, int maxCandidates, int maxActiveDeliveries,
                                 Instant updatedAt) {
}
