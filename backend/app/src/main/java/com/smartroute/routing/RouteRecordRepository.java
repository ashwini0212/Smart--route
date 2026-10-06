package com.smartroute.routing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface RouteRecordRepository extends JpaRepository<RouteRecord, Long> {

    Page<RouteRecord> findByRequestedByOrderByCreatedAtDesc(Long requestedBy, Pageable pageable);
}
