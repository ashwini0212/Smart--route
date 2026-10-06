package com.smartroute.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One consumed event, stored so people can read the event stream (the System Events page). */
@Entity
@Table(name = "system_event")
class SystemEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(nullable = false, length = 80)
    private String topic;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 60)
    private String aggregateId;

    @Column(nullable = false, length = 300)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    protected SystemEvent() {
    }

    SystemEvent(EventEnvelope envelope, String summary, String payloadJson) {
        this.eventId = UUID.fromString(envelope.eventId());
        this.eventType = envelope.type().name();
        this.eventVersion = envelope.version();
        this.topic = envelope.type().topic();
        this.aggregateType = envelope.aggregateType();
        this.aggregateId = envelope.aggregateId();
        this.summary = summary.length() > 300 ? summary.substring(0, 300) : summary;
        this.payload = payloadJson;
        this.correlationId = envelope.correlationId();
        this.occurredAt = envelope.occurredAt();
    }

    Long getId() {
        return id;
    }

    UUID getEventId() {
        return eventId;
    }

    String getEventType() {
        return eventType;
    }

    int getEventVersion() {
        return eventVersion;
    }

    String getTopic() {
        return topic;
    }

    String getAggregateType() {
        return aggregateType;
    }

    String getAggregateId() {
        return aggregateId;
    }

    String getSummary() {
        return summary;
    }

    String getPayload() {
        return payload;
    }

    String getCorrelationId() {
        return correlationId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getRecordedAt() {
        return recordedAt;
    }
}
