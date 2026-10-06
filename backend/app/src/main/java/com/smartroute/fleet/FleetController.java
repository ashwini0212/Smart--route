package com.smartroute.fleet;

import com.smartroute.common.security.Access;
import com.smartroute.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Fleet", description = "Drivers and vehicles")
class FleetController {

    private final DriverService drivers;
    private final VehicleService vehicles;

    FleetController(DriverService drivers, VehicleService vehicles) {
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    @GetMapping("/drivers")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "List drivers, optionally filtered by status")
    PageResponse<DriverResponse> listDrivers(@RequestParam(required = false) DriverStatus status,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(drivers.list(status, PageRequest.of(page, size, Sort.by("code"))), d -> d);
    }

    @GetMapping("/drivers/{id}")
    @PreAuthorize(Access.STAFF_OR_VIEWER + " or @access.isDriver(#id)")
    @Operation(summary = "Get a driver")
    DriverResponse getDriver(@PathVariable long id) {
        return drivers.get(id);
    }

    @PostMapping("/drivers")
    @PreAuthorize(Access.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a driver (code is generated)")
    DriverResponse createDriver(@Valid @RequestBody DriverRequest request) {
        return drivers.create(request);
    }

    @PutMapping("/drivers/{id}")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Update a driver's profile, home warehouse or vehicle")
    DriverResponse updateDriver(@PathVariable long id, @Valid @RequestBody DriverRequest request) {
        return drivers.update(id, request);
    }

    @PutMapping("/drivers/{id}/status")
    @PreAuthorize(Access.STAFF + " or @access.isDriver(#id)")
    @Operation(summary = "Change a driver's shift status (AVAILABLE, ON_BREAK, OFFLINE)")
    DriverResponse changeDriverStatus(@PathVariable long id, @RequestParam DriverStatus status) {
        return drivers.changeStatus(id, status);
    }

    @GetMapping("/vehicles")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "List vehicles, optionally filtered by type")
    PageResponse<VehicleResponse> listVehicles(@RequestParam(required = false) VehicleType type,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(vehicles.list(type, PageRequest.of(page, size, Sort.by("plateNumber"))), v -> v);
    }

    @GetMapping("/vehicles/{id}")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Get a vehicle")
    VehicleResponse getVehicle(@PathVariable long id) {
        return vehicles.get(id);
    }

    @PostMapping("/vehicles")
    @PreAuthorize(Access.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a vehicle")
    VehicleResponse createVehicle(@Valid @RequestBody VehicleRequest request) {
        return vehicles.create(request);
    }

    @PutMapping("/vehicles/{id}/status")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Change a vehicle's status")
    VehicleResponse changeVehicleStatus(@PathVariable long id, @RequestParam VehicleStatus status) {
        return vehicles.changeStatus(id, status);
    }
}
