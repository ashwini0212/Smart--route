package com.smartroute.order;

import com.smartroute.fleet.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;

/** What the assignment engine needs to know about an order (read-only). */
public record OrderAssignmentView(long id, String code, long warehouseId, OrderPriority priority, OrderStatus status,
                                  BigDecimal weightKg, BigDecimal volumeM3, VehicleType requiredVehicleType,
                                  Instant windowEnd, Instant createdAt, Long driverId) {

    static OrderAssignmentView from(DeliveryOrder o) {
        return new OrderAssignmentView(o.getId(), o.getCode(), o.getWarehouseId(), o.getPriority(), o.getStatus(),
                o.getWeightKg(), o.getVolumeM3(), o.getRequiredVehicleType(), o.getWindowEnd(), o.getCreatedAt(),
                o.getDriverId());
    }
}
