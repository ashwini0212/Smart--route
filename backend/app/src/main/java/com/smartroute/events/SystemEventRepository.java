package com.smartroute.events;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SystemEventRepository extends JpaRepository<SystemEvent, Long> {

    Page<SystemEvent> findByEventType(String eventType, Pageable pageable);

    Page<SystemEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId, Pageable pageable);
}
