package com.smartroute.warehouse;

public record WarehouseResponse(Long id, String code, String name, String address,
                                double latitude, double longitude, boolean active) {

    static WarehouseResponse from(Warehouse w) {
        return new WarehouseResponse(w.getId(), w.getCode(), w.getName(), w.getAddress(),
                w.getLatitude(), w.getLongitude(), w.isActive());
    }
}
