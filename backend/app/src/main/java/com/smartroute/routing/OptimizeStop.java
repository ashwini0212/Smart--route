package com.smartroute.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One stop to visit.
 *
 * @param label         what to call it in the answer (an order code, a customer name)
 * @param dueBy         latest acceptable arrival; a stop that cannot be reached in time is flagged, not dropped
 * @param serviceMinutes how long the driver spends at the stop (adds to every later arrival)
 */
public record OptimizeStop(
        @Size(max = 60) String label,
        @NotNull @Valid GeoPoint location,
        @DecimalMin("0") @DecimalMax("20000") BigDecimal weightKg,
        @DecimalMin("0") @DecimalMax("100") BigDecimal volumeM3,
        Instant dueBy,
        @DecimalMin("0") @DecimalMax("240") Integer serviceMinutes) {

    BigDecimal weightOrZero() {
        return weightKg == null ? BigDecimal.ZERO : weightKg;
    }

    BigDecimal volumeOrZero() {
        return volumeM3 == null ? BigDecimal.ZERO : volumeM3;
    }

    int serviceOrZero() {
        return serviceMinutes == null ? 0 : serviceMinutes;
    }
}
