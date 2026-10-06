package com.smartroute.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Envelope parsing, including the two cases that must go to the dead-letter topic instead of being guessed at. */
class EventEnvelopeReaderTest {

    private final EventEnvelopeReader reader = new EventEnvelopeReader(JsonMapper.builder().build());

    private static String envelope(String type, int version, String payload) {
        return """
                {"eventId":"11111111-1111-1111-1111-111111111111","type":"%s","version":%d,\
                "aggregateType":"order","aggregateId":"42","occurredAt":"2026-10-06T10:15:30Z",\
                "correlationId":"trace-1","payload":%s}""".formatted(type, version, payload);
    }

    @Test
    void readsEveryEnvelopeField() {
        EventEnvelope envelope = reader.read(envelope("ORDER_CREATED", 1,
                """
                {"orderId":42,"code":"ORD-1","weightKg":12.5,"express":true,"reason":null}"""));

        assertThat(envelope.eventId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(envelope.type()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.aggregateType()).isEqualTo("order");
        assertThat(envelope.aggregateId()).isEqualTo("42");
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-10-06T10:15:30Z"));
        assertThat(envelope.correlationId()).isEqualTo("trace-1");
        assertThat(envelope.payload())
                .containsEntry("orderId", 42L)
                .containsEntry("code", "ORD-1")
                .containsEntry("weightKg", 12.5)
                .containsEntry("express", true)
                .containsEntry("reason", null);
    }

    @Test
    void anUnknownEventTypeIsRejected() {
        // A consumer that ignored unknown types would silently drop events a newer producer sends.
        assertThatThrownBy(() -> reader.read(envelope("ORDER_TELEPORTED", 1, "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORDER_TELEPORTED");
    }

    @Test
    void aNewerPayloadVersionIsRejected() {
        assertThatThrownBy(() -> reader.read(envelope("ORDER_CREATED", 2, "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("understands up to 1");
    }

    @Test
    void anOlderPayloadVersionIsAccepted() {
        // Old events stay readable: that is what the version is for.
        assertThat(reader.read(envelope("ORDER_CREATED", 1, "{}")).version()).isEqualTo(1);
    }

    @Test
    void aMissingEnvelopeFieldIsRejected() {
        assertThatThrownBy(() -> reader.read("""
                {"type":"ORDER_CREATED","version":1,"aggregateType":"order","aggregateId":"42",\
                "occurredAt":"2026-10-06T10:15:30Z","payload":{}}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void aMissingCorrelationIdIsAllowed() {
        EventEnvelope envelope = reader.read("""
                {"eventId":"11111111-1111-1111-1111-111111111111","type":"DRIVER_LOCATION_UPDATED","version":1,\
                "aggregateType":"driver","aggregateId":"7","occurredAt":"2026-10-06T10:15:30Z",\
                "correlationId":null,"payload":{"latitude":12.9}}""");

        assertThat(envelope.correlationId()).isNull();
        assertThat(envelope.payload()).containsEntry("latitude", 12.9);
    }

    @Test
    void nestedPayloadValuesAreKeptAsJson() {
        EventEnvelope envelope = reader.read(envelope("ROUTE_RECALCULATED", 1, """
                {"stops":[1,2,3]}"""));

        assertThat(envelope.payload().get("stops")).isEqualTo("[1,2,3]");
    }
}
