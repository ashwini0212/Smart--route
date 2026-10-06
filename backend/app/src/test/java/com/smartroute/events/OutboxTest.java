package com.smartroute.events;

import com.smartroute.common.security.Role;
import com.smartroute.fleet.DriverService;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the application writes to the outbox, and the property the outbox exists for: the event and the
 * business change commit together or not at all.
 *
 * <p>Runs in the default test context, which has no broker ({@code smartroute.events.enabled=false}): the
 * outbox is written regardless, because that is part of the transaction, not of the publishing.
 */
class OutboxTest extends ApiTestSupport {

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private DomainEvents domainEvents;

    @Autowired
    private DriverService drivers;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EventStreamService eventStream;

    private long warehouseId;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-EVENTS");
    }

    private long createOrder() throws Exception {
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":12.99,"dropLongitude":77.62,"priority":"NORMAL","weightKg":10,"volumeM3":0.5}
                """.formatted(warehouseId)).andExpect(status().isCreated())).get("id").asLong();
    }

    private List<OutboxEvent> eventsAbout(String aggregateType, long id) {
        return outbox.findByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(aggregateType, Long.toString(id));
    }

    @Test
    void creatingAnOrderWritesOneEventWithItsPayload() throws Exception {
        long orderId = createOrder();

        List<OutboxEvent> events = eventsAbout("order", orderId);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EventType.ORDER_CREATED.name());
        assertThat(event.getTopic()).isEqualTo(EventType.Topics.ORDER_CREATED);
        // The key is the aggregate id, which is what keeps one order's events on one partition and in order.
        assertThat(event.getPartitionKey()).isEqualTo(Long.toString(orderId));
        assertThat(event.getEventVersion()).isEqualTo(1);
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payload.get("status").asString()).isEqualTo("CREATED");
        assertThat(payload.get("warehouseId").asLong()).isEqualTo(warehouseId);
        assertThat(payload.get("priority").asString()).isEqualTo("NORMAL");
        assertThat(payload.get("weightKg").asDouble()).isEqualTo(10.0);
        assertThat(payload.get("previousStatus").isNull()).isTrue();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void theOrderLifecycleProducesOneEventPerStepThatOtherSystemsCareAbout() throws Exception {
        long vehicleId = createVehicle("KA01-E-0001", "VAN");
        long driverId = createDriver(warehouseId, vehicleId);
        drivers.updateLocation(driverId, 12.97, 77.59, Instant.now());
        putJson("/api/drivers/" + driverId + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        long orderId = createOrder();
        String driverToken = users.driverToken(driverId);

        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(orderId, driverId)).andExpect(status().isCreated());
        for (String next : List.of("PICKED_UP", "IN_TRANSIT", "DELIVERED")) {
            putJson("/api/deliveries/" + orderId + "/status", "{\"status\":\"%s\"}".formatted(next), driverToken)
                    .andExpect(status().isOk());
        }

        // IN_TRANSIT is a step inside a delivery, so it is deliberately not an event.
        assertThat(eventsAbout("order", orderId).stream().map(OutboxEvent::getEventType))
                .containsExactly(EventType.ORDER_CREATED.name(), EventType.ORDER_ASSIGNED.name(),
                        EventType.DELIVERY_STARTED.name(), EventType.DELIVERY_COMPLETED.name());
    }

    @Test
    void unassigningIsItsOwnEventOnTheAssignmentTopic() throws Exception {
        long vehicleId = createVehicle("KA01-E-0002", "VAN");
        long driverId = createDriver(warehouseId, vehicleId);
        drivers.updateLocation(driverId, 12.97, 77.59, Instant.now());
        putJson("/api/drivers/" + driverId + "/status?status=AVAILABLE", "").andExpect(status().isOk());
        long orderId = createOrder();
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(orderId, driverId)).andExpect(status().isCreated());

        postJson("/api/orders/" + orderId + "/unassign", "{\"reason\":\"Customer asked to delay\"}")
                .andExpect(status().isOk());

        List<OutboxEvent> events = eventsAbout("order", orderId);
        assertThat(events.stream().map(OutboxEvent::getEventType)).containsExactly(EventType.ORDER_CREATED.name(),
                EventType.ORDER_ASSIGNED.name(), EventType.ORDER_UNASSIGNED.name());
        // Same topic and key as the assignment, so a consumer tracking "who has this order" reads both in order.
        assertThat(events.getLast().getTopic()).isEqualTo(EventType.Topics.ORDER_ASSIGNED);
        assertThat(events.getLast().getPayload()).contains("Customer asked to delay");
    }

    @Test
    void aDriverLocationUpdateIsAnEventKeyedByDriver() throws Exception {
        long vehicleId = createVehicle("KA01-E-0003", "VAN");
        long driverId = createDriver(warehouseId, vehicleId);

        drivers.updateLocation(driverId, 12.95, 77.60, Instant.now());

        List<OutboxEvent> events = eventsAbout("driver", driverId);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EventType.DRIVER_LOCATION_UPDATED.name());
        assertThat(events.getFirst().getPartitionKey()).isEqualTo(Long.toString(driverId));
        assertThat(objectMapper.readTree(events.getFirst().getPayload()).get("latitude").asDouble())
                .isEqualTo(12.95);
    }

    @Test
    void anEventIsRolledBackWithTheChangeItDescribes() {
        // The whole point of the outbox: no event about something that did not happen.
        transactions.executeWithoutResult(status -> {
            domainEvents.append(EventType.ORDER_CREATED, "order", "999", Map.of("orderId", 999));
            status.setRollbackOnly();
        });

        assertThat(eventsAbout("order", 999)).isEmpty();
    }

    @Test
    void appendingOutsideATransactionFails() {
        // Guards the rule above: a caller that is not in a transaction cannot write an event at all.
        assertThatThrownBy(() -> domainEvents.append(EventType.ORDER_CREATED, "order", "998", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theOutboxEndpointCountsWhatIsWaitingAndIsAdminOnly() throws Exception {
        createOrder();

        getUrl("/api/events/outbox")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.published").value(0));
        getUrl("/api/events/outbox", users.token(Role.DISPATCHER)).andExpect(status().isForbidden());
    }

    @Test
    void retentionDeletesPublishedEventsOnlyAfterTheirWindow() {
        // keep-published is 7 days by default; a row published now must survive.
        transactions.executeWithoutResult(status ->
                domainEvents.append(EventType.ORDER_CREATED, "order", "997", Map.of("orderId", 997)));
        OutboxEvent event = eventsAbout("order", 997).getFirst();
        transactions.executeWithoutResult(status -> {
            OutboxEvent managed = outbox.findById(event.getId()).orElseThrow();
            managed.markPublished(Instant.now());
            outbox.save(managed);
        });

        assertThat(eventStream.deletePublishedOlderThanRetention()).isZero();
        assertThat(eventsAbout("order", 997)).hasSize(1);
    }
}
