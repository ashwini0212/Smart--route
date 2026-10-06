package com.smartroute.fleet;

import com.smartroute.common.error.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VehicleService {

    private final VehicleRepository vehicles;
    private final DriverRepository drivers;

    VehicleService(VehicleRepository vehicles, DriverRepository drivers) {
        this.vehicles = vehicles;
        this.drivers = drivers;
    }

    @Transactional
    public VehicleResponse create(VehicleRequest request) {
        if (vehicles.existsByPlateNumber(request.plateNumber())) {
            throw ApiException.duplicate("Vehicle " + request.plateNumber() + " already exists");
        }
        Vehicle vehicle = vehicles.save(new Vehicle(request.plateNumber(), request.type(),
                request.maxWeightKg(), request.maxVolumeM3()));
        return VehicleResponse.from(vehicle);
    }

    @Transactional
    public VehicleResponse changeStatus(long id, VehicleStatus status) {
        Vehicle vehicle = load(id);
        if (status != VehicleStatus.ACTIVE && drivers.existsByVehicleId(id)) {
            throw ApiException.businessRule("Unassign vehicle " + vehicle.getPlateNumber() + " from its driver first");
        }
        vehicle.setStatus(status);
        return VehicleResponse.from(vehicle);
    }

    public VehicleResponse get(long id) {
        return VehicleResponse.from(load(id));
    }

    public Page<VehicleResponse> list(VehicleType type, Pageable pageable) {
        Page<Vehicle> page = type == null ? vehicles.findAll(pageable) : vehicles.findByType(type, pageable);
        return page.map(VehicleResponse::from);
    }

    Vehicle load(long id) {
        return vehicles.findById(id).orElseThrow(() -> ApiException.notFound("Vehicle", id));
    }
}
