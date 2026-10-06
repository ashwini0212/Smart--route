package com.smartroute.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** @param count how many routes to return at most, including the best one */
public record AlternativesRequest(
        @NotNull @Valid GeoPoint from,
        @NotNull @Valid GeoPoint to,
        @NotNull RouteMode mode,
        @Min(1) @Max(3) int count) {
}
