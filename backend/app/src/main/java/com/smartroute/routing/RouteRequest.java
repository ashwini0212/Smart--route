package com.smartroute.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RouteRequest(@NotNull @Valid GeoPoint from, @NotNull @Valid GeoPoint to) {
}
