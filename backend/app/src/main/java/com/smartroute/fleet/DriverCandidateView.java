package com.smartroute.fleet;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the assignment engine needs to know about a driver: availability, position, vehicle and load.
 * Read-only; the actual reservation happens in {@link DriverService#reserveCapacity}.
 */
public record DriverCandidateView(
        long id,
        String code,
        DriverStatus status,
        Double latitude,
        Double longitude,
        VehicleType vehicleType,
        VehicleStatus vehicleStatus,
        BigDecimal maxWeightKg,
        BigDecimal maxVolumeM3,
        BigDecimal loadKg,
        BigDecimal loadM3,
        int activeDeliveries,
        Instant locationUpdatedAt) {

    public boolean hasVehicle() {
        return vehicleType != null;
    }

    public BigDecimal remainingKg() {
        return hasVehicle() ? maxWeightKg.subtract(loadKg) : BigDecimal.ZERO;
    }

    public BigDecimal remainingM3() {
        return hasVehicle() ? maxVolumeM3.subtract(loadM3) : BigDecimal.ZERO;
    }
}
