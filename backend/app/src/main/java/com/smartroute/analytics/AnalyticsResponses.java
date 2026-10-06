package com.smartroute.analytics;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The analytics responses.
 *
 * <p>Every one of these numbers is an aggregate over rows the system actually wrote, and each carries the
 * definition it was computed with. That is not decoration: "on-time rate" and "utilization" mean whatever the
 * query says they mean, and a dashboard that shows a percentage without its definition is a dashboard nobody
 * can argue with.
 */
public final class AnalyticsResponses {

    private AnalyticsResponses() {
    }

    @Schema(description = "Headline numbers for a time window")
    public record Overview(
            Instant from,
            Instant to,
            int days,
            @Schema(description = "Orders created in the window, by their status now")
            Map<String, Long> ordersByStatus,
            long created,
            long delivered,
            long failed,
            long cancelled,
            @Schema(description = "Orders waiting for a driver right now, whenever they were created")
            long waitingNow,
            @Schema(description = "Deliveries under way right now (assigned, picked up or in transit)")
            long activeNow,
            @Schema(description = "Delivered in the window that had a delivery window to be judged against")
            long deliveredWithWindow,
            long onTime,
            long late,
            @Schema(description = "onTime / deliveredWithWindow; null when nothing in the window had a deadline")
            Double onTimeRate,
            @Schema(description = "Minutes from assignment to delivery, over deliveries completed in the window")
            Duration assignedToDeliveredMinutes,
            List<String> definitions) {
    }

    @Schema(description = "A distribution, reported as the percentiles rather than a single average")
    public record Duration(long samples, Double p50, Double p90, Double mean) {
    }

    @Schema(description = "One day of the window")
    public record ThroughputDay(LocalDate day, long created, long delivered, long failed, long cancelled) {
    }

    @Schema(description = "What each driver did in the window")
    public record DriverPerformance(
            long driverId,
            String driverCode,
            String status,
            long delivered,
            long late,
            long failed,
            int activeNow,
            @Schema(description = "Median minutes from assignment to delivery for this driver")
            Double medianMinutes) {
    }

    public record FleetUsage(
            int driverCount,
            @Schema(description = "Drivers who completed at least one delivery in the window")
            int driversWithDeliveries,
            @Schema(description = "driversWithDeliveries / driverCount — a share of the fleet, not a share of their time")
            Double shareOfFleetUsed,
            @Schema(description = "Deliveries completed in the window, divided by the drivers who completed any")
            Double deliveriesPerActiveDriver,
            List<DriverPerformance> perDriver,
            List<String> definitions) {
    }

    @Schema(description = "Predicted pickup travel time against the time a pickup actually took")
    public record EtaAccuracy(
            long samples,
            @Schema(description = "Median of the ETA recorded with the assignment, in minutes")
            Double predictedMedianMinutes,
            @Schema(description = "Median of assignment → picked up, in minutes")
            Double actualMedianMinutes,
            @Schema(description = "Median of (actual − predicted), in minutes; positive means pickups took longer")
            Double medianDifferenceMinutes,
            Double p90DifferenceMinutes,
            long withinFiveMinutes,
            List<String> definitions) {
    }
}
