package com.smartroute.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Scoring weights and limits (single row, id 1), editable by an admin at runtime. */
@Entity
@Table(name = "assignment_config")
class AssignmentConfig {

    static final long SINGLETON_ID = 1;

    @Id
    private Long id;

    @Column(name = "eta_weight", nullable = false)
    private double etaWeight;

    @Column(name = "workload_weight", nullable = false)
    private double workloadWeight;

    @Column(name = "capacity_weight", nullable = false)
    private double capacityWeight;

    @Column(name = "eta_cap_seconds", nullable = false)
    private int etaCapSeconds;

    @Column(name = "search_radius_meters", nullable = false)
    private int searchRadiusMeters;

    @Column(name = "max_candidates", nullable = false)
    private int maxCandidates;

    @Column(name = "max_active_deliveries", nullable = false)
    private int maxActiveDeliveries;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AssignmentConfig() {
    }

    void update(AssignmentConfigRequest r) {
        etaWeight = r.etaWeight();
        workloadWeight = r.workloadWeight();
        capacityWeight = r.capacityWeight();
        etaCapSeconds = r.etaCapSeconds();
        searchRadiusMeters = r.searchRadiusMeters();
        maxCandidates = r.maxCandidates();
        maxActiveDeliveries = r.maxActiveDeliveries();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    AssignmentSettings toSettings() {
        return new AssignmentSettings(etaWeight, workloadWeight, capacityWeight, etaCapSeconds, searchRadiusMeters,
                maxCandidates, maxActiveDeliveries, updatedAt);
    }
}
