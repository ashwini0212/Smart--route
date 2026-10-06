package com.smartroute.fleet;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record VehicleRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9-]{4,20}", message = "must be 4-20 characters: A-Z, 0-9 or -") String plateNumber,
        @NotNull VehicleType type,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("50000") BigDecimal maxWeightKg,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("200") BigDecimal maxVolumeM3) {
}
