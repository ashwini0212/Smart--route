package com.smartroute.fleet;

import java.math.BigDecimal;

public record VehicleResponse(Long id, String plateNumber, VehicleType type, BigDecimal maxWeightKg,
                              BigDecimal maxVolumeM3, VehicleStatus status) {

    static VehicleResponse from(Vehicle v) {
        return new VehicleResponse(v.getId(), v.getPlateNumber(), v.getType(), v.getMaxWeightKg(),
                v.getMaxVolumeM3(), v.getStatus());
    }
}
