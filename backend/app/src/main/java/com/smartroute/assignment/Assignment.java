package com.smartroute.assignment;

import com.smartroute.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One assignment decision and the score that justified it. Kept even after the order is unassigned or
 * delivered: it is the audit trail ("why did this driver get this order?") and input for analytics.
 */
@Entity
@Table(name = "assignment")
class Assignment extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentMethod method;

    @Column(name = "assigned_by")
    private Long assignedBy;

    private Double score;

    @Column(name = "eta_seconds")
    private Double etaSeconds;

    @Column(name = "eta_score")
    private Double etaScore;

    @Column(name = "workload_score")
    private Double workloadScore;

    @Column(name = "capacity_score")
    private Double capacityScore;

    @Column(name = "candidate_rank")
    private Integer candidateRank;

    protected Assignment() {
    }

    /** @param candidate the driver's entry in the ranking, or null if the driver wasn't ranked */
    Assignment(long orderId, long driverId, AssignmentMethod method, Long assignedBy, Candidate candidate) {
        this.orderId = orderId;
        this.driverId = driverId;
        this.method = method;
        this.assignedBy = assignedBy;
        if (candidate != null) {
            this.score = candidate.score();
            this.etaSeconds = candidate.etaSeconds();
            this.etaScore = candidate.etaScore();
            this.workloadScore = candidate.workloadScore();
            this.capacityScore = candidate.capacityScore();
            this.candidateRank = candidate.rank();
        }
    }

    Long getOrderId() {
        return orderId;
    }

    Long getDriverId() {
        return driverId;
    }

    AssignmentMethod getMethod() {
        return method;
    }

    Long getAssignedBy() {
        return assignedBy;
    }

    Double getScore() {
        return score;
    }

    Double getEtaSeconds() {
        return etaSeconds;
    }

    Double getEtaScore() {
        return etaScore;
    }

    Double getWorkloadScore() {
        return workloadScore;
    }

    Double getCapacityScore() {
        return capacityScore;
    }

    Integer getCandidateRank() {
        return candidateRank;
    }
}
