package com.smartroute.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The last route the sweep computed for a driver, so a recalculation can state what changed instead of
 * publishing an event every time the sweep runs.
 */
@Entity
@Table(name = "driver_route_snapshot")
class DriverRouteSnapshot {

    /** Stop labels are order codes; the column is 400 characters, which is about 30 stops. */
    static final int MAX_SEQUENCE_LENGTH = 400;

    @Id
    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "network_version", nullable = false)
    private long networkVersion;

    @Column(name = "duration_seconds", nullable = false)
    private double durationSeconds;

    @Column(name = "stop_sequence", nullable = false, length = MAX_SEQUENCE_LENGTH)
    private String stopSequence;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected DriverRouteSnapshot() {
    }

    DriverRouteSnapshot(long driverId, long networkVersion, double durationSeconds, String stopSequence,
                        Instant computedAt) {
        this.driverId = driverId;
        update(networkVersion, durationSeconds, stopSequence, computedAt);
    }

    void update(long networkVersion, double durationSeconds, String stopSequence, Instant computedAt) {
        this.networkVersion = networkVersion;
        this.durationSeconds = durationSeconds;
        this.stopSequence = stopSequence.length() > MAX_SEQUENCE_LENGTH
                ? stopSequence.substring(0, MAX_SEQUENCE_LENGTH) : stopSequence;
        this.computedAt = computedAt;
    }

    long getNetworkVersion() {
        return networkVersion;
    }

    double getDurationSeconds() {
        return durationSeconds;
    }

    String getStopSequence() {
        return stopSequence;
    }

    Instant getComputedAt() {
        return computedAt;
    }
}
