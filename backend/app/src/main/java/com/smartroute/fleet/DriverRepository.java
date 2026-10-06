package com.smartroute.fleet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface DriverRepository extends JpaRepository<Driver, Long> {

    boolean existsByVehicleId(Long vehicleId);

    Page<Driver> findByStatus(DriverStatus status, Pageable pageable);

    List<Driver> findByStatusIn(List<DriverStatus> statuses);

    @Query(value = "SELECT nextval('driver_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
