package com.smartroute.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** What has already been reported as late, so one slipping delivery raises one alert, not one per sweep. */
@Entity
@Table(name = "delivery_alert")
class DeliveryAlert {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "late_by_seconds", nullable = false)
    private int lateBySeconds;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    protected DeliveryAlert() {
    }

    DeliveryAlert(long orderId, int lateBySeconds, Instant reportedAt) {
        this.orderId = orderId;
        this.lateBySeconds = lateBySeconds;
        this.reportedAt = reportedAt;
    }

    void update(int lateBySeconds, Instant reportedAt) {
        this.lateBySeconds = lateBySeconds;
        this.reportedAt = reportedAt;
    }

    Long getOrderId() {
        return orderId;
    }

    int getLateBySeconds() {
        return lateBySeconds;
    }

    Instant getReportedAt() {
        return reportedAt;
    }
}
