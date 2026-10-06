package com.smartroute.events;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses an envelope from a Kafka record.
 *
 * <p>Unknown event types and newer payload versions are errors, not something to guess at: a consumer that
 * silently ignores a field it does not understand produces wrong analytics rather than an alert. Both end up
 * in the dead-letter topic, where they can be inspected.
 */
@Component
public class EventEnvelopeReader {

    private final ObjectMapper objectMapper;

    EventEnvelopeReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventEnvelope read(String json) {
        JsonNode node = objectMapper.readTree(json);
        String typeName = text(node, "type");
        EventType type;
        try {
            type = EventType.valueOf(typeName);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown event type '" + typeName + "'");
        }
        int version = node.path("version").asInt(0);
        if (version > type.version()) {
            throw new IllegalArgumentException("Event " + typeName + " has version " + version
                    + "; this consumer understands up to " + type.version());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        JsonNode payloadNode = node.path("payload");
        payloadNode.propertyNames().forEach(name -> payload.put(name, scalar(payloadNode.get(name))));
        return new EventEnvelope(text(node, "eventId"), type, version, text(node, "aggregateType"),
                text(node, "aggregateId"), Instant.parse(text(node, "occurredAt")),
                node.path("correlationId").isNull() ? null : node.path("correlationId").asString(null), payload);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asString("").isBlank()) {
            throw new IllegalArgumentException("Event envelope is missing '" + field + "'");
        }
        return value.asString();
    }

    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            // Written as two returns on purpose: in one ternary, long and double are promoted to double,
            // which would turn every id in a payload into a floating-point number.
            if (node.isIntegralNumber()) {
                return node.asLong();
            }
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.isObject() || node.isArray() ? node.toString() : node.asString();
    }
}
