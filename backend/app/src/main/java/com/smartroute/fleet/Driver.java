package com.smartroute.fleet;

import com.smartroute.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A driver and their live operational state (status, load, last known position).
 *
 * <p>References to other aggregates (warehouse, vehicle) are stored as ids rather than JPA
 * relationships. That keeps module boundaries explicit and avoids accidental lazy-loading queries.
 */
@Entity
@Table(name = "driver")
public class Driver extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "vehicle_id", unique = true)
    private Long vehicleId;

    @Column(name = "home_warehouse_id", nullable = false)
    private Long homeWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriverStatus status = DriverStatus.OFFLINE;

    @Column(name = "current_load_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentLoadKg = BigDecimal.ZERO;

    @Column(name = "current_load_m3", nullable = false, precision = 10, scale = 3)
    private BigDecimal currentLoadM3 = BigDecimal.ZERO;

    @Column(name = "active_delivery_count", nullable = false)
    private int activeDeliveryCount;

    @Column(name = "last_latitude")
    private Double lastLatitude;

    @Column(name = "last_longitude")
    private Double lastLongitude;

    @Column(name = "last_location_at")
    private Instant lastLocationAt;

    protected Driver() {
    }

    public Driver(String code, String fullName, String phone, Long homeWarehouseId, Long vehicleId) {
        this.code = code;
        this.fullName = fullName;
        this.phone = phone;
        this.homeWarehouseId = homeWarehouseId;
        this.vehicleId = vehicleId;
    }

    public void updateProfile(String fullName, String phone, Long homeWarehouseId, Long vehicleId) {
        this.fullName = fullName;
        this.phone = phone;
        this.homeWarehouseId = homeWarehouseId;
        this.vehicleId = vehicleId;
    }

    public void changeStatus(DriverStatus status) {
        this.status = status;
    }

    public void recordLocation(double latitude, double longitude, Instant at) {
        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.lastLocationAt = at;
    }

    public void addLoad(BigDecimal weightKg, BigDecimal volumeM3) {
        currentLoadKg = currentLoadKg.add(weightKg);
        currentLoadM3 = currentLoadM3.add(volumeM3);
        activeDeliveryCount++;
    }

    public void removeLoad(BigDecimal weightKg, BigDecimal volumeM3) {
        currentLoadKg = currentLoadKg.subtract(weightKg).max(BigDecimal.ZERO);
        currentLoadM3 = currentLoadM3.subtract(volumeM3).max(BigDecimal.ZERO);
        activeDeliveryCount = Math.max(0, activeDeliveryCount - 1);
    }

    public String getCode() {
        return code;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public Long getHomeWarehouseId() {
        return homeWarehouseId;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public BigDecimal getCurrentLoadKg() {
        return currentLoadKg;
    }

    public BigDecimal getCurrentLoadM3() {
        return currentLoadM3;
    }

    public int getActiveDeliveryCount() {
        return activeDeliveryCount;
    }

    public Double getLastLatitude() {
        return lastLatitude;
    }

    public Double getLastLongitude() {
        return lastLongitude;
    }

    public Instant getLastLocationAt() {
        return lastLocationAt;
    }
}
