package com.smartroute.events;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest unpublished events first: the relay's only read, served by the partial index. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit limit);

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNotNull();

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(String aggregateType, String aggregateId);

    long deleteByPublishedAtBefore(Instant before);
}
