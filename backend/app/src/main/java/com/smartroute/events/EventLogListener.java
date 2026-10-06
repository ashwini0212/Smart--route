package com.smartroute.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The first consumer: it records every event in {@code system_event} so people can read the stream.
 *
 * <p>One listener for all topics, because this consumer treats them alike. It is a separate consumer group,
 * so later consumers (notifications, analytics) read the same events independently and at their own pace —
 * that is the point of a log: one producer, many readers, no coordination.
 */
@Component
@ConditionalOnProperty(name = "smartroute.events.enabled", matchIfMissing = true)
class EventLogListener {

    static final String GROUP = "event-log";
    private static final Logger log = LoggerFactory.getLogger(EventLogListener.class);

    private final EventEnvelopeReader reader;
    private final EventConsumers consumers;
    private final SystemEventRepository systemEvents;
    private final ObjectMapper objectMapper;

    EventLogListener(EventEnvelopeReader reader, EventConsumers consumers, SystemEventRepository systemEvents,
                     ObjectMapper objectMapper) {
        this.reader = reader;
        this.consumers = consumers;
        this.systemEvents = systemEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(groupId = GROUP, topics = {
            EventType.Topics.ORDER_CREATED,
            EventType.Topics.ORDER_ASSIGNED,
            EventType.Topics.DELIVERY_STARTED,
            EventType.Topics.DELIVERY_COMPLETED,
            EventType.Topics.DELIVERY_DELAYED,
            EventType.Topics.ROUTE_RECALCULATED,
            EventType.Topics.DRIVER_LOCATION})
    void onEvent(String message) {
        EventEnvelope envelope = reader.read(message);
        consumers.handleOnce(GROUP, envelope, this::record);
        log.debug("Logged event {} ({})", envelope.eventId(), envelope.type());
    }

    private void record(EventEnvelope envelope) {
        systemEvents.save(new SystemEvent(envelope, summarize(envelope),
                objectMapper.writeValueAsString(envelope.payload())));
    }

    /** A line a person can read in a list, built from the payload fields each event type is known to carry. */
    private static String summarize(EventEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        return switch (envelope.type()) {
            case ORDER_CREATED -> "Order %s created at warehouse %s (%s)"
                    .formatted(code(payload), payload.get("warehouseId"), payload.get("priority"));
            case ORDER_ASSIGNED -> "Order %s assigned to driver %s".formatted(code(payload), payload.get("driverId"));
            case ORDER_UNASSIGNED -> "Order %s taken from driver %s: %s"
                    .formatted(code(payload), payload.get("driverId"), payload.get("reason"));
            case ORDER_CANCELLED -> "Order %s cancelled: %s".formatted(code(payload), payload.get("reason"));
            case DELIVERY_STARTED -> "Driver %s picked up order %s".formatted(payload.get("driverId"), code(payload));
            case DELIVERY_COMPLETED -> "Order %s delivered by driver %s".formatted(code(payload), payload.get("driverId"));
            case DELIVERY_FAILED -> "Order %s failed: %s".formatted(code(payload), payload.get("reason"));
            case DELIVERY_DELAYED -> "Order %s is late by %s s".formatted(code(payload), payload.get("lateBySeconds"));
            case ROUTE_RECALCULATED -> "Route recalculated for %s: %s".formatted(envelope.aggregateId(),
                    payload.get("reason"));
            case DRIVER_LOCATION_UPDATED -> "Driver %s moved to %s, %s".formatted(envelope.aggregateId(),
                    payload.get("latitude"), payload.get("longitude"));
        };
    }

    private static Object code(Map<String, Object> payload) {
        return payload.getOrDefault("code", payload.get("orderId"));
    }
}
