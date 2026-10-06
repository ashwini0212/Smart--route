package com.smartroute.fleet;

import com.smartroute.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "vehicle")
public class Vehicle extends BaseEntity {

    @Column(name = "plate_number", nullable = false, unique = true, length = 20)
    private String plateNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleType type;

    @Column(name = "max_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "max_volume_m3", nullable = false, precision = 10, scale = 3)
    private BigDecimal maxVolumeM3;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleStatus status = VehicleStatus.ACTIVE;

    protected Vehicle() {
    }

    public Vehicle(String plateNumber, VehicleType type, BigDecimal maxWeightKg, BigDecimal maxVolumeM3) {
        this.plateNumber = plateNumber;
        this.type = type;
        this.maxWeightKg = maxWeightKg;
        this.maxVolumeM3 = maxVolumeM3;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public VehicleType getType() {
        return type;
    }

    public BigDecimal getMaxWeightKg() {
        return maxWeightKg;
    }

    public BigDecimal getMaxVolumeM3() {
        return maxVolumeM3;
    }

    public VehicleStatus getStatus() {
        return status;
    }
}
