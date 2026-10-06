package com.smartroute.events;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

interface SystemEventRepository extends JpaRepository<SystemEvent, Long> {

    /**
     * Slices, not pages: the event log is append-only and large, so the total a Page would carry costs a
     * full count and is stale the moment it is read. See {@link com.smartroute.common.web.SliceResponse}.
     */
    Slice<SystemEvent> findByEventType(String eventType, Pageable pageable);

    Slice<SystemEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId, Pageable pageable);

    /** Unfiltered, as a slice; {@code findAll(Pageable)} from JpaRepository would count the table. */
    Slice<SystemEvent> findAllBy(Pageable pageable);
}
