package com.smartroute.warehouse;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WarehouseRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9-]{2,20}", message = "must be 2-20 characters: A-Z, 0-9 or -") String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String address,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {
}
