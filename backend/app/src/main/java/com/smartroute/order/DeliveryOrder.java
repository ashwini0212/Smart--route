package com.smartroute.order;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import com.smartroute.common.persistence.BaseEntity;
import com.smartroute.fleet.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** A customer delivery from a warehouse to a drop location. Named DeliveryOrder because ORDER is SQL. */
@Entity
@Table(name = "delivery_order")
public class DeliveryOrder extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "drop_address", nullable = false)
    private String dropAddress;

    @Column(name = "drop_latitude", nullable = false)
    private double dropLatitude;

    @Column(name = "drop_longitude", nullable = false)
    private double dropLongitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "volume_m3", nullable = false, precision = 10, scale = 3)
    private BigDecimal volumeM3;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_vehicle_type", length = 20)
    private VehicleType requiredVehicleType;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    protected DeliveryOrder() {
    }

    DeliveryOrder(String code, Long warehouseId, String customerName, String dropAddress, double dropLatitude,
                  double dropLongitude, OrderPriority priority, BigDecimal weightKg, BigDecimal volumeM3,
                  VehicleType requiredVehicleType, Instant windowStart, Instant windowEnd) {
        this.code = code;
        this.warehouseId = warehouseId;
        this.customerName = customerName;
        this.dropAddress = dropAddress;
        this.dropLatitude = dropLatitude;
        this.dropLongitude = dropLongitude;
        this.priority = priority;
        this.weightKg = weightKg;
        this.volumeM3 = volumeM3;
        this.requiredVehicleType = requiredVehicleType;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    /** Moves to {@code target} if the state machine allows it; returns the previous status. */
    OrderStatus transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Order " + code + " cannot move from " + status + " to " + target);
        }
        OrderStatus previous = status;
        status = target;
        return previous;
    }

    public String getCode() {
        return code;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getDropAddress() {
        return dropAddress;
    }

    public double getDropLatitude() {
        return dropLatitude;
    }

    public double getDropLongitude() {
        return dropLongitude;
    }

    public OrderPriority getPriority() {
        return priority;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public BigDecimal getVolumeM3() {
        return volumeM3;
    }

    public VehicleType getRequiredVehicleType() {
        return requiredVehicleType;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }
}
