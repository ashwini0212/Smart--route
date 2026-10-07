package com.smartroute.tracking;

import com.smartroute.events.EventEnvelope;
import com.smartroute.events.EventEnvelopeReader;
import com.smartroute.fleet.LocationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The second consumer group: it keeps the live read model and the dashboard up to date.
 *
 * <p>It reads the same topics as the event log and is a separate group, so neither consumer waits for the
 * other — the point of a log. Unlike the event log it does <em>not</em> claim events in
 * {@code processed_event}: its two side effects are a last-write-wins position in Redis and a frame pushed to
 * a browser. Handling the same event twice writes the same position and sends one extra frame, so paying for
 * a database claim per event (and losing the ability to replay the topic into a rebuilt read model) would
 * buy nothing. Exactly-once matters where a duplicate would change stored history, which is the event log.
 */
@Component
@ConditionalOnProperty(name = "smartroute.events.enabled", matchIfMissing = true)
class LiveFeedListener {

    static final String GROUP = "live-feed";
    private static final Logger log = LoggerFactory.getLogger(LiveFeedListener.class);

    private final EventEnvelopeReader reader;
    private final LivePositions positions;
    private final LiveStream stream;

    LiveFeedListener(EventEnvelopeReader reader, LivePositions positions, LiveStream stream) {
        this.reader = reader;
        this.positions = positions;
        this.stream = stream;
    }

    // Every event topic, whichever layout is configured (see EventTopics).
    @KafkaListener(groupId = GROUP, topics = "#{@eventTopics.subscriptions()}")
    void onEvent(String message) {
        EventEnvelope envelope = reader.read(message);
        switch (envelope.type()) {
            case DRIVER_LOCATION_UPDATED -> driverMoved(envelope);
            case DELIVERY_DELAYED -> stream.publish("delivery-delayed", frame(envelope));
            case ROUTE_RECALCULATED -> stream.publish("route-recalculated", frame(envelope));
            default -> stream.publish("order-status", frame(envelope));
        }
    }

    private void driverMoved(EventEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        LivePosition incoming = new LivePosition(
                Long.parseLong(envelope.aggregateId()),
                number(payload.get("latitude")),
                number(payload.get("longitude")),
                Instant.parse((String) payload.get("at")),
                source(payload.get("source")));
        LivePosition current = positions.record(incoming);
        if (current.at().isAfter(incoming.at())) {
            // An older position arrived late; the dashboard already shows something newer.
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("driverId", current.driverId());
        frame.put("latitude", current.latitude());
        frame.put("longitude", current.longitude());
        frame.put("at", current.at().toString());
        frame.put("source", current.source().name());
        stream.publish("driver-moved", frame);
    }

    /** Everything the dashboard needs about a non-position event, without re-sending the whole payload. */
    private static Map<String, Object> frame(EventEnvelope envelope) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("eventId", envelope.eventId());
        frame.put("type", envelope.type().name());
        frame.put("aggregateType", envelope.aggregateType());
        frame.put("aggregateId", envelope.aggregateId());
        frame.put("occurredAt", envelope.occurredAt().toString());
        frame.put("payload", envelope.payload());
        return frame;
    }

    private static double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("Expected a number, got " + value);
    }

    private static LocationSource source(Object value) {
        if (value == null) {
            // Events published before Phase 10 carry no source; they were all reported through the API.
            return LocationSource.API;
        }
        try {
            return LocationSource.valueOf(value.toString());
        } catch (IllegalArgumentException unknown) {
            log.warn("Unknown location source '{}', treating it as API", value);
            return LocationSource.API;
        }
    }
}
