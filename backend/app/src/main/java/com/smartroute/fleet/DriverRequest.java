package com.smartroute.fleet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DriverRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Pattern(regexp = "\\+?[0-9 -]{8,20}", message = "must be a phone number") String phone,
        @NotNull Long homeWarehouseId,
        Long vehicleId) {
}
