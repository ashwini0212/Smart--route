package com.smartroute.warehouse;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Package-private: other modules go through {@link WarehouseService}, never this repository. */
interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByCode(String code);

    List<Warehouse> findAllByOrderByCodeAsc();
}
