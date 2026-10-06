package com.smartroute.fleet;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import com.smartroute.warehouse.WarehouseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class DriverService {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final WarehouseService warehouses;

    DriverService(DriverRepository drivers, VehicleRepository vehicles, WarehouseService warehouses) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.warehouses = warehouses;
    }

    @Transactional
    public DriverResponse create(DriverRequest request) {
        warehouses.requireActive(request.homeWarehouseId());
        requireAssignableVehicle(request.vehicleId(), null);
        String code = "DRV-%06d".formatted(drivers.nextCodeNumber());
        Driver driver = drivers.save(new Driver(code, request.fullName(), request.phone(),
                request.homeWarehouseId(), request.vehicleId()));
        return DriverResponse.from(driver);
    }

    @Transactional
    public DriverResponse update(long id, DriverRequest request) {
        Driver driver = load(id);
        if (!Objects.equals(driver.getHomeWarehouseId(), request.homeWarehouseId())) {
            warehouses.requireActive(request.homeWarehouseId());
        }
        if (!Objects.equals(driver.getVehicleId(), request.vehicleId())) {
            if (driver.getActiveDeliveryCount() > 0) {
                throw ApiException.businessRule("Cannot change the vehicle of a driver with active deliveries");
            }
            requireAssignableVehicle(request.vehicleId(), id);
        }
        driver.updateProfile(request.fullName(), request.phone(), request.homeWarehouseId(), request.vehicleId());
        return DriverResponse.from(driver);
    }

    /**
     * Manual status change (by a dispatcher or the driver). ON_DELIVERY is set only by the system when
     * deliveries are assigned, so it can't be requested here.
     */
    @Transactional
    public DriverResponse changeStatus(long id, DriverStatus target) {
        Driver driver = load(id);
        if (target == DriverStatus.ON_DELIVERY) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "ON_DELIVERY is set automatically by assignments");
        }
        if (target == DriverStatus.AVAILABLE && driver.getVehicleId() == null) {
            throw ApiException.businessRule("Driver " + driver.getCode() + " needs a vehicle before going on shift");
        }
        driver.changeStatus(target);
        return DriverResponse.from(driver);
    }

    /** Latest known GPS position (from the location stream in Phase 10, or seed data). */
    @Transactional
    public void updateLocation(long id, double latitude, double longitude, java.time.Instant at) {
        load(id).recordLocation(latitude, longitude, at);
    }

    public long count() {
        return drivers.count();
    }

    public DriverResponse get(long id) {
        return DriverResponse.from(load(id));
    }

    public Page<DriverResponse> list(DriverStatus status, Pageable pageable) {
        Page<Driver> page = status == null ? drivers.findAll(pageable) : drivers.findByStatus(status, pageable);
        return page.map(DriverResponse::from);
    }

    Driver load(long id) {
        return drivers.findById(id).orElseThrow(() -> ApiException.notFound("Driver", id));
    }

    private void requireAssignableVehicle(Long vehicleId, Long driverId) {
        if (vehicleId == null) {
            return;
        }
        Vehicle vehicle = vehicles.findById(vehicleId)
                .orElseThrow(() -> ApiException.businessRule("Vehicle " + vehicleId + " does not exist"));
        if (vehicle.getStatus() != VehicleStatus.ACTIVE) {
            throw ApiException.businessRule("Vehicle " + vehicle.getPlateNumber() + " is " + vehicle.getStatus());
        }
        if (drivers.existsByVehicleId(vehicleId)) {
            throw ApiException.businessRule("Vehicle " + vehicle.getPlateNumber() + " is already assigned to another driver");
        }
    }
}
