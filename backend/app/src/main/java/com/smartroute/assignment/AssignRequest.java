package com.smartroute.assignment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AssignRequest(@NotNull Long orderId, @NotNull Long driverId, @Size(max = 255) String reason) {
}
