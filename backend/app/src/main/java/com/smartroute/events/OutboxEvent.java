package com.smartroute.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A domain event waiting to be published (or already published, kept as a record of what was sent). */
@Entity
@Table(name = "outbox_event")
class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 60)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(nullable = false, length = 80)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 60)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEvent() {
    }

    OutboxEvent(UUID id, EventType type, String aggregateType, String aggregateId, String partitionKey,
                String payload, String correlationId, Instant occurredAt) {
        this.id = id;
        this.eventType = type.name();
        this.eventVersion = type.version();
        this.topic = type.topic();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    void markPublished(Instant at) {
        this.publishedAt = at;
        this.attempts++;
        this.lastError = null;
    }

    void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    UUID getId() {
        return id;
    }

    String getTopic() {
        return topic;
    }

    String getPartitionKey() {
        return partitionKey;
    }

    String getPayload() {
        return payload;
    }

    String getEventType() {
        return eventType;
    }

    int getEventVersion() {
        return eventVersion;
    }

    String getAggregateType() {
        return aggregateType;
    }

    String getAggregateId() {
        return aggregateId;
    }

    String getCorrelationId() {
        return correlationId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    int getAttempts() {
        return attempts;
    }

    String getLastError() {
        return lastError;
    }
}
