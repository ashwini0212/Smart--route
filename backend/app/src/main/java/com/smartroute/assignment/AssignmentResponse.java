package com.smartroute.assignment;

import java.time.Instant;

/** One recorded assignment decision. Score fields are null when the driver was not in the ranking. */
public record AssignmentResponse(Long id, Long orderId, Long driverId, AssignmentMethod method, Long assignedBy,
                                 Double score, Double etaSeconds, Double etaScore, Double workloadScore,
                                 Double capacityScore, Integer candidateRank, Instant createdAt) {

    static AssignmentResponse from(Assignment a) {
        return new AssignmentResponse(a.getId(), a.getOrderId(), a.getDriverId(), a.getMethod(), a.getAssignedBy(),
                a.getScore(), a.getEtaSeconds(), a.getEtaScore(), a.getWorkloadScore(), a.getCapacityScore(),
                a.getCandidateRank(), a.getCreatedAt());
    }
}
