package com.smartroute.fleet;

import java.math.BigDecimal;
import java.time.Instant;

public record DriverResponse(Long id, String code, String fullName, String phone, Long homeWarehouseId,
                             Long vehicleId, DriverStatus status, BigDecimal currentLoadKg,
                             BigDecimal currentLoadM3, int activeDeliveryCount,
                             Double lastLatitude, Double lastLongitude, Instant lastLocationAt) {

    static DriverResponse from(Driver d) {
        return new DriverResponse(d.getId(), d.getCode(), d.getFullName(), d.getPhone(), d.getHomeWarehouseId(),
                d.getVehicleId(), d.getStatus(), d.getCurrentLoadKg(), d.getCurrentLoadM3(),
                d.getActiveDeliveryCount(), d.getLastLatitude(), d.getLastLongitude(), d.getLastLocationAt());
    }
}
