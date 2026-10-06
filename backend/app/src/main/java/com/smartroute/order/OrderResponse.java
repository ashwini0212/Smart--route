package com.smartroute.order;

import com.smartroute.fleet.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, String code, Long warehouseId, String customerName, String dropAddress,
                            double dropLatitude, double dropLongitude, OrderPriority priority, OrderStatus status,
                            BigDecimal weightKg, BigDecimal volumeM3, VehicleType requiredVehicleType,
                            Instant windowStart, Instant windowEnd, Long driverId, Instant assignedAt,
                            Instant createdAt, Instant updatedAt) {

    static OrderResponse from(DeliveryOrder o) {
        return new OrderResponse(o.getId(), o.getCode(), o.getWarehouseId(), o.getCustomerName(), o.getDropAddress(),
                o.getDropLatitude(), o.getDropLongitude(), o.getPriority(), o.getStatus(), o.getWeightKg(),
                o.getVolumeM3(), o.getRequiredVehicleType(), o.getWindowStart(), o.getWindowEnd(),
                o.getDriverId(), o.getAssignedAt(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
