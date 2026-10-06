package com.smartroute.routing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Ask for a visiting order over up to 20 stops.
 *
 * @param strategy      AUTO picks exact or heuristic by size; EXACT and HEURISTIC force one (for comparing them)
 * @param returnToStart whether the way back to the start counts in the total
 * @param departAt      when the driver leaves the start; defaults to now. Arrival times are relative to it
 * @param capacityKg    optional vehicle capacity; exceeding it is an error, not something to silently ignore
 */
public record OptimizeRequest(
        @NotNull @Valid GeoPoint start,
        @NotEmpty @Size(max = 20) List<@Valid OptimizeStop> stops,
        RouteMode mode,
        OptimizeStrategy strategy,
        Boolean returnToStart,
        Instant departAt,
        @DecimalMin("0") BigDecimal capacityKg,
        @DecimalMin("0") BigDecimal capacityM3) {

    // The name must start with "is": Bean Validation only treats getter-shaped methods as properties.
    @Schema(hidden = true)
    @AssertTrue(message = "stops must not repeat the same location")
    public boolean isEveryStopDistinct() {
        return stops == null || stops.stream().map(OptimizeStop::location).distinct().count() == stops.size();
    }

    /** Absent means "no return leg": the next assignment decides where the driver goes after the last stop. */
    boolean returnsToStart() {
        return Boolean.TRUE.equals(returnToStart);
    }

    RouteMode modeOrDefault() {
        return mode == null ? RouteMode.FASTEST : mode;
    }

    OptimizeStrategy strategyOrDefault() {
        return strategy == null ? OptimizeStrategy.AUTO : strategy;
    }
}
