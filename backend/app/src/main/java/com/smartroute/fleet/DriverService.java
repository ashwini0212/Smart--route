package com.smartroute.fleet;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import com.smartroute.warehouse.WarehouseService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class DriverService {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final WarehouseService warehouses;
    private final ApplicationEventPublisher events;

    DriverService(DriverRepository drivers, VehicleRepository vehicles, WarehouseService warehouses,
                  ApplicationEventPublisher events) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.warehouses = warehouses;
        this.events = events;
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
        DriverStatus previous = driver.getStatus();
        // Back from a break with deliveries still on board: the driver is on delivery, not idle.
        DriverStatus effective = target == DriverStatus.AVAILABLE && driver.getActiveDeliveryCount() > 0
                ? DriverStatus.ON_DELIVERY : target;
        driver.changeStatus(effective);
        if (previous != effective) {
            // Synchronous, same transaction: e.g. going OFFLINE re-queues unstarted orders atomically.
            events.publishEvent(new DriverStatusChangedEvent(id, driver.getCode(), previous, effective));
        }
        return DriverResponse.from(driver);
    }

    /** Latest known GPS position (from the location stream in Phase 10, or seed data). */
    @Transactional
    public void updateLocation(long id, double latitude, double longitude, Instant at) {
        updateLocation(id, latitude, longitude, at, LocationSource.API);
    }

    /**
     * Records a position and says where it came from; the source travels with the event (see
     * {@link LocationSource}).
     *
     * <p>Annotated even though the method above delegates to it: this class is {@code readOnly} by default, so
     * without it a caller of this overload writes nothing at all (Hibernate skips the flush) and the location
     * event's outbox row disappears with it.
     */
    @Transactional
    public void updateLocation(long id, double latitude, double longitude, Instant at, LocationSource source) {
        load(id).recordLocation(latitude, longitude, at);
        events.publishEvent(new DriverLocationChangedEvent(id, latitude, longitude, at, source));
    }

    /**
     * Books an order's weight and volume onto a driver, under a row lock.
     *
     * <p>The candidate list was computed from data read earlier, possibly before another dispatcher (or
     * the auto-dispatcher) gave this driver another order. So every rule is checked again here, on the
     * locked row: this is the step that makes concurrent assignment safe (no driver over capacity).
     */
    @Transactional
    public void reserveCapacity(long driverId, BigDecimal weightKg, BigDecimal volumeM3, VehicleType requiredType,
                                int maxActiveDeliveries) {
        Driver driver = drivers.findByIdForUpdate(driverId).orElseThrow(() -> ApiException.notFound("Driver", driverId));
        if (!driver.getStatus().canReceiveOrders()) {
            throw ApiException.businessRule("Driver " + driver.getCode() + " is " + driver.getStatus());
        }
        if (driver.getActiveDeliveryCount() >= maxActiveDeliveries) {
            throw ApiException.businessRule("Driver " + driver.getCode() + " already has "
                    + driver.getActiveDeliveryCount() + " active deliveries (limit " + maxActiveDeliveries + ")");
        }
        Vehicle vehicle = driver.getVehicleId() == null ? null : vehicles.findById(driver.getVehicleId()).orElse(null);
        if (vehicle == null || vehicle.getStatus() != VehicleStatus.ACTIVE) {
            throw ApiException.businessRule("Driver " + driver.getCode() + " has no active vehicle");
        }
        if (!vehicle.getType().satisfies(requiredType)) {
            throw ApiException.businessRule("Order needs a " + requiredType + "; driver " + driver.getCode()
                    + " drives a " + vehicle.getType());
        }
        if (driver.getCurrentLoadKg().add(weightKg).compareTo(vehicle.getMaxWeightKg()) > 0
                || driver.getCurrentLoadM3().add(volumeM3).compareTo(vehicle.getMaxVolumeM3()) > 0) {
            throw ApiException.businessRule("Order does not fit: driver " + driver.getCode() + " has "
                    + vehicle.getMaxWeightKg().subtract(driver.getCurrentLoadKg()) + " kg / "
                    + vehicle.getMaxVolumeM3().subtract(driver.getCurrentLoadM3()) + " m³ left");
        }
        driver.addLoad(weightKg, volumeM3);
        if (driver.getStatus() == DriverStatus.AVAILABLE) {
            driver.changeStatus(DriverStatus.ON_DELIVERY);
        }
    }

    /** Removes an order's load (delivered, failed, cancelled or re-queued); an empty driver becomes AVAILABLE. */
    @Transactional
    public void releaseCapacity(long driverId, BigDecimal weightKg, BigDecimal volumeM3) {
        Driver driver = drivers.findByIdForUpdate(driverId).orElseThrow(() -> ApiException.notFound("Driver", driverId));
        driver.removeLoad(weightKg, volumeM3);
        if (driver.getActiveDeliveryCount() == 0 && driver.getStatus() == DriverStatus.ON_DELIVERY) {
            driver.changeStatus(DriverStatus.AVAILABLE);
        }
    }

    /** Availability, position, vehicle and load of the given drivers, in one query each for drivers and vehicles. */
    public List<DriverCandidateView> candidates(Collection<Long> ids) {
        return views(drivers.findAllById(ids));
    }

    /** Every driver with a known position (used to build the location index and as its fallback). */
    public List<DriverCandidateView> allLocated() {
        return views(drivers.findAllLocated());
    }

    private List<DriverCandidateView> views(List<Driver> list) {
        List<Long> vehicleIds = list.stream().map(Driver::getVehicleId).filter(Objects::nonNull).toList();
        Map<Long, Vehicle> byId = new HashMap<>();
        vehicles.findAllById(vehicleIds).forEach(v -> byId.put(v.getId(), v));
        return list.stream().map(d -> {
            Vehicle v = d.getVehicleId() == null ? null : byId.get(d.getVehicleId());
            return new DriverCandidateView(d.getId(), d.getCode(), d.getStatus(), d.getLastLatitude(),
                    d.getLastLongitude(), v == null ? null : v.getType(), v == null ? null : v.getStatus(),
                    v == null ? null : v.getMaxWeightKg(), v == null ? null : v.getMaxVolumeM3(),
                    d.getCurrentLoadKg(), d.getCurrentLoadM3(), d.getActiveDeliveryCount(), d.getLastLocationAt());
        }).toList();
    }

    public long count() {
        return drivers.count();
    }

    public boolean exists(long id) {
        return drivers.existsById(id);
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
