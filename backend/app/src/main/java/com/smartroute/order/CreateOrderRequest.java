package com.smartroute.order;

import com.smartroute.fleet.VehicleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateOrderRequest(
        @NotNull Long warehouseId,
        @NotBlank @Size(max = 120) String customerName,
        @NotBlank @Size(max = 255) String dropAddress,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double dropLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double dropLongitude,
        @NotNull OrderPriority priority,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("20000") BigDecimal weightKg,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal volumeM3,
        VehicleType requiredVehicleType,
        Instant windowStart,
        Instant windowEnd) {

    @Schema(hidden = true)
    @AssertTrue(message = "windowEnd must be after windowStart")
    public boolean isTimeWindowValid() {
        return windowStart == null || windowEnd == null || windowEnd.isAfter(windowStart);
    }
}
