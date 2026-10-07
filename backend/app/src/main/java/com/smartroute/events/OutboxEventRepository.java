package com.smartroute.events;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest unpublished events first: the relay's only read, served by the partial index. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit limit);

    long countByPublishedAtIsNull();

    /**
     * Pending events that have already failed at least once.
     *
     * <p>{@code lastError} was written on every failed publish and read by nothing: a relay that kept failing
     * looked exactly like a relay with a backlog. This is the smallest honest way to tell them apart — a count,
     * not the message, because the message is an exception string and those belong in the log, not in a
     * response body.
     */
    long countByPublishedAtIsNullAndLastErrorIsNotNull();

    /**
     * An exact count of what has been published. Only the tests ask for this: it is a sequential scan, which
     * is why the endpoint reports {@link #estimatedRowCount()} minus the pending backlog instead.
     */
    long countByPublishedAtIsNotNull();

    /**
     * The planner's row estimate for the table, which costs one lookup in pg_class instead of reading every
     * row. Published rows are only ever counted for display, and a count(*) over them is a sequential scan
     * that gets slower for the lifetime of the deployment.
     */
    @Query(value = "SELECT reltuples::bigint FROM pg_class WHERE relname = 'outbox_event'", nativeQuery = true)
    long estimatedRowCount();

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(String aggregateType, String aggregateId);

    long deleteByPublishedAtBefore(Instant before);
}
