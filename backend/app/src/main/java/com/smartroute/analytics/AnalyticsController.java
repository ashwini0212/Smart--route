package com.smartroute.analytics;

import com.smartroute.analytics.AnalyticsResponses.EtaAccuracy;
import com.smartroute.analytics.AnalyticsResponses.FleetUsage;
import com.smartroute.analytics.AnalyticsResponses.Overview;
import com.smartroute.analytics.AnalyticsResponses.ThroughputDay;
import com.smartroute.common.security.Access;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only analytics for operations (FR-23).
 *
 * <p>Open to viewers as well as staff: the whole point of the viewer role is that a manager can read the
 * numbers without being able to change anything. Drivers are not included — these are fleet-wide aggregates.
 */
@RestController
@RequestMapping("/api/analytics")
@Validated
@Tag(name = "Analytics", description = "Fleet-wide numbers, each with the definition it was computed with")
class AnalyticsController {

    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Headline numbers for the last `days` days, with their definitions")
    Overview overview(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return analytics.overview(days);
    }

    @GetMapping("/throughput")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Orders created, delivered, failed and cancelled per day (UTC)")
    List<ThroughputDay> throughput(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return analytics.throughput(days);
    }

    @GetMapping("/fleet")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "What each driver completed in the window, and how much of the fleet worked at all")
    FleetUsage fleet(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return analytics.fleetUsage(days, limit);
    }

    @GetMapping("/eta-accuracy")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Predicted pickup travel time against how long pickups actually took (an upper bound)")
    EtaAccuracy etaAccuracy(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return analytics.etaAccuracy(days);
    }
}
