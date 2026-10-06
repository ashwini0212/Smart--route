package com.smartroute.warehouse;

import com.smartroute.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class WarehouseService {

    private final WarehouseRepository repository;

    WarehouseService(WarehouseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WarehouseResponse create(WarehouseRequest request) {
        if (repository.existsByCode(request.code())) {
            throw ApiException.duplicate("Warehouse code " + request.code() + " already exists");
        }
        Warehouse saved = repository.save(new Warehouse(request.code(), request.name(), request.address(),
                request.latitude(), request.longitude()));
        return WarehouseResponse.from(saved);
    }

    @Transactional
    public WarehouseResponse update(long id, WarehouseRequest request) {
        Warehouse warehouse = load(id);
        if (!warehouse.getCode().equals(request.code())) {
            throw ApiException.businessRule("Warehouse code cannot be changed");
        }
        warehouse.update(request.name(), request.address(), request.latitude(), request.longitude());
        return WarehouseResponse.from(warehouse);
    }

    @Transactional
    public WarehouseResponse setActive(long id, boolean active) {
        Warehouse warehouse = load(id);
        warehouse.setActive(active);
        return WarehouseResponse.from(warehouse);
    }

    public WarehouseResponse get(long id) {
        return WarehouseResponse.from(load(id));
    }

    public List<WarehouseResponse> list() {
        return repository.findAllByOrderByCodeAsc().stream().map(WarehouseResponse::from).toList();
    }

    /** For other modules: fails with 422 if the warehouse is missing or inactive. */
    public WarehouseResponse requireActive(long id) {
        Warehouse warehouse = repository.findById(id)
                .orElseThrow(() -> ApiException.businessRule("Warehouse " + id + " does not exist"));
        if (!warehouse.isActive()) {
            throw ApiException.businessRule("Warehouse " + warehouse.getCode() + " is inactive");
        }
        return WarehouseResponse.from(warehouse);
    }

    public long count() {
        return repository.count();
    }

    private Warehouse load(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Warehouse", id));
    }
}
