package com.smartroute.warehouse;

import com.smartroute.common.security.Access;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Every authenticated user may read warehouses (drivers need their hub); only ADMIN changes them. */
@RestController
@RequestMapping("/api/warehouses")
@Tag(name = "Warehouses", description = "Pickup hubs")
class WarehouseController {

    private final WarehouseService service;

    WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List all warehouses")
    List<WarehouseResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a warehouse")
    WarehouseResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(Access.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a warehouse")
    WarehouseResponse create(@Valid @RequestBody WarehouseRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Update name, address or position (code is immutable)")
    WarehouseResponse update(@PathVariable long id, @Valid @RequestBody WarehouseRequest request) {
        return service.update(id, request);
    }

    @PutMapping("/{id}/active")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Activate or deactivate a warehouse")
    WarehouseResponse setActive(@PathVariable long id, @RequestParam boolean active) {
        return service.setActive(id, active);
    }
}
