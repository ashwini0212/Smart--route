package com.smartroute.events;

import java.time.Instant;
import java.util.Map;

/**
 * What goes on the wire. The envelope is the same for every event; only {@code payload} differs.
 *
 * <p>Why each field exists:
 * <ul>
 *   <li>{@code eventId} — the deduplication key. Delivery is at-least-once, so a consumer must be able to
 *       recognise an event it already handled.</li>
 *   <li>{@code type} and {@code version} — a consumer dispatches on the type and can refuse a version it
 *       does not understand, instead of guessing at unknown fields.</li>
 *   <li>{@code aggregateType}/{@code aggregateId} — what the event is about, without parsing the payload.</li>
 *   <li>{@code occurredAt} — when the change happened, which is not when the event was delivered.</li>
 *   <li>{@code correlationId} — the request's trace id, so one user action can be followed across
 *       services and logs.</li>
 * </ul>
 */
public record EventEnvelope(
        String eventId,
        EventType type,
        int version,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        Map<String, Object> payload) {
}
