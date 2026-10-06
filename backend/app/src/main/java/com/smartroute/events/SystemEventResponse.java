package com.smartroute.events;

import java.time.Instant;

/** One event as the System Events page shows it. */
public record SystemEventResponse(Long id, String eventId, String eventType, int eventVersion, String topic,
                                  String aggregateType, String aggregateId, String summary, String payload,
                                  String correlationId, Instant occurredAt, Instant recordedAt) {

    static SystemEventResponse from(SystemEvent e) {
        return new SystemEventResponse(e.getId(), e.getEventId().toString(), e.getEventType(), e.getEventVersion(),
                e.getTopic(), e.getAggregateType(), e.getAggregateId(), e.getSummary(), e.getPayload(),
                e.getCorrelationId(), e.getOccurredAt(), e.getRecordedAt());
    }
}
