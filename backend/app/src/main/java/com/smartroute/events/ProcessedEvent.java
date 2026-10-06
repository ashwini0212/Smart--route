package com.smartroute.events;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Marks one event as handled by one consumer group. The primary key is what makes a retry a no-op. */
@Entity
@Table(name = "processed_event")
class ProcessedEvent {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    ProcessedEvent(String consumerGroup, UUID eventId) {
        this.key = new Key(consumerGroup, eventId);
    }

    @Embeddable
    record Key(@Column(name = "consumer_group", length = 60) String consumerGroup,
               @Column(name = "event_id") UUID eventId) implements Serializable {
    }
}
