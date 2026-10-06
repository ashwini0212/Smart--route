package com.smartroute.events;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Reads the recorded event stream, and keeps the outbox from growing without bound. */
@Service
@Transactional(readOnly = true)
@EnableConfigurationProperties(EventsProperties.class)
public class EventStreamService {

    private final SystemEventRepository systemEvents;
    private final OutboxEventRepository outbox;
    private final EventsProperties properties;
    private final java.time.Clock clock;

    EventStreamService(SystemEventRepository systemEvents, OutboxEventRepository outbox, EventsProperties properties,
                       java.time.Clock clock) {
        this.systemEvents = systemEvents;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A slice of the recorded stream. Not a page: counting the table cost 40 ms per view at 300k events, for
     * a total that the relay had already made wrong.
     */
    public Slice<SystemEventResponse> search(String eventType, String aggregateType, String aggregateId,
                                             Pageable pageable) {
        Slice<SystemEvent> slice;
        if (aggregateType != null && aggregateId != null) {
            slice = systemEvents.findByAggregateTypeAndAggregateId(aggregateType, aggregateId, pageable);
        } else if (eventType != null) {
            slice = systemEvents.findByEventType(eventType, pageable);
        } else {
            slice = systemEvents.findAllBy(pageable);
        }
        return slice.map(SystemEventResponse::from);
    }

    /** Published rows older than the retention window; unpublished rows are never deleted. */
    @Transactional
    public long deletePublishedOlderThanRetention() {
        return outbox.deleteByPublishedAtBefore(clock.instant().minus(properties.keepPublished()));
    }

    /**
     * Outbox depth. {@code pending} is exact and cheap: the partial index on unpublished rows holds only the
     * backlog, which is the number this endpoint exists for. {@code publishedEstimate} is deliberately an
     * estimate — counting the published rows meant a sequential scan of the whole table, 52 ms at 320k rows
     * and growing with every event the system has ever sent, to answer a question nobody makes a decision
     * on. PostgreSQL's own row estimate for the table, minus the exact backlog, answers it for nothing.
     */
    public OutboxStatus outboxStatus() {
        long pending = outbox.countByPublishedAtIsNull();
        long rows = Math.max(outbox.estimatedRowCount(), pending);
        return new OutboxStatus(pending, rows - pending, Instant.now());
    }

    /**
     * How many events are waiting to be published; a growing {@code pending} means the relay is stuck.
     * {@code publishedEstimate} comes from the table's statistics, so it moves in steps as autovacuum
     * refreshes them and can be out by a few per cent.
     */
    public record OutboxStatus(long pending, long publishedEstimate, Instant at) {
    }
}
