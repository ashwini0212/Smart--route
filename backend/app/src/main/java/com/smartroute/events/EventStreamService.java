package com.smartroute.events;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

    public Page<SystemEventResponse> search(String eventType, String aggregateType, String aggregateId,
                                            Pageable pageable) {
        Page<SystemEvent> page;
        if (aggregateType != null && aggregateId != null) {
            page = systemEvents.findByAggregateTypeAndAggregateId(aggregateType, aggregateId, pageable);
        } else if (eventType != null) {
            page = systemEvents.findByEventType(eventType, pageable);
        } else {
            page = systemEvents.findAll(pageable);
        }
        return page.map(SystemEventResponse::from);
    }

    /** Published rows older than the retention window; unpublished rows are never deleted. */
    @Transactional
    public long deletePublishedOlderThanRetention() {
        return outbox.deleteByPublishedAtBefore(clock.instant().minus(properties.keepPublished()));
    }

    public OutboxStatus outboxStatus() {
        return new OutboxStatus(outbox.countByPublishedAtIsNull(), outbox.countByPublishedAtIsNotNull(),
                Instant.now());
    }

    /** How many events are waiting to be published; a growing {@code pending} means the relay is stuck. */
    public record OutboxStatus(long pending, long published, Instant at) {
    }
}
