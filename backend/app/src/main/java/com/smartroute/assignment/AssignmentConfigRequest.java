package com.smartroute.assignment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AssignmentConfigRequest(
        @DecimalMin("0") @DecimalMax("10") double etaWeight,
        @DecimalMin("0") @DecimalMax("10") double workloadWeight,
        @DecimalMin("0") @DecimalMax("10") double capacityWeight,
        @Min(60) @Max(14_400) int etaCapSeconds,
        @Min(100) @Max(100_000) int searchRadiusMeters,
        @Min(1) @Max(500) int maxCandidates,
        @Min(1) @Max(50) int maxActiveDeliveries) {

    @Schema(hidden = true)
    @AssertTrue(message = "at least one weight must be greater than 0")
    public boolean isAnyWeightPositive() {
        return etaWeight + workloadWeight + capacityWeight > 0;
    }
}
