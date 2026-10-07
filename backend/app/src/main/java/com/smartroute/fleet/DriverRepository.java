package com.smartroute.fleet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface DriverRepository extends JpaRepository<Driver, Long> {

    boolean existsByVehicleId(Long vehicleId);

    Page<Driver> findByStatus(DriverStatus status, Pageable pageable);

    /**
     * {@code SELECT ... FOR UPDATE}: a second transaction reserving capacity on the same driver waits here
     * until the first commits, then sees the updated load. Used only where a conflict is expected.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Driver d WHERE d.id = :id")
    Optional<Driver> findByIdForUpdate(@Param("id") long id);

    @Query("SELECT d FROM Driver d WHERE d.lastLatitude IS NOT NULL")
    List<Driver> findAllLocated();

    @Query(value = "SELECT nextval('driver_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
