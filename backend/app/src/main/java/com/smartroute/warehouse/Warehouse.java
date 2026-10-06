package com.smartroute.warehouse;

import com.smartroute.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A pickup hub. Orders ship from a warehouse; drivers have a home warehouse. */
@Entity
@Table(name = "warehouse")
public class Warehouse extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private boolean active = true;

    protected Warehouse() {
        // for JPA
    }

    public Warehouse(String code, String name, String address, double latitude, double longitude) {
        this.code = code;
        update(name, address, latitude, longitude);
    }

    public void update(String name, String address, double latitude, double longitude) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public boolean isActive() {
        return active;
    }
}
