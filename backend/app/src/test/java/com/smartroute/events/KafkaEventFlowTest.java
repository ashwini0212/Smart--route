package com.smartroute.events;

import com.smartroute.common.security.Role;
import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.LocationSource;
import com.smartroute.tracking.LivePosition;
import com.smartroute.tracking.LivePositions;
import com.smartroute.support.ApiTestSupport;
import com.smartroute.support.KafkaTestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The event path end to end on a real broker: outbox → relay → Kafka → consumer → {@code system_event}.
 *
 * <p>This test enables publishing (the rest of the suite runs with {@code smartroute.events.enabled=false})
 * and parks the relay's schedule an hour out, calling {@link OutboxRelay#relayOnce()} itself: the same code
 * the scheduler calls, but at a moment the test knows about, which is what makes the assertions below about
 * what is published and what is still pending meaningful.
 *
 * <p>Topics outlive a test method (only the database is emptied between tests), so records are looked up by
 * their event id rather than by position.
 */
@SpringBootTest(properties = {
        "smartroute.events.enabled=true",
        // The scheduler would otherwise publish before a test has looked at the unpublished state.
        "smartroute.events.relay-interval=1h"})
@Import(KafkaTestcontainersConfiguration.class)
class KafkaEventFlowTest extends ApiTestSupport {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRelayScheduler scheduler;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DriverService drivers;

    @Autowired
    private LivePositions positions;

    @Autowired
    private KafkaContainer kafkaContainer;

    private long warehouseId;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-KAFKA");
    }

    private long createOrder() throws Exception {
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":12.99,"dropLongitude":77.62,"priority":"NORMAL","weightKg":10,"volumeM3":0.5}
                """.formatted(warehouseId)).andExpect(status().isCreated())).get("id").asLong();
    }

    private long recordedEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM system_event", Long.class);
    }

    /** Reads a topic from its beginning until one record matches, so other tests' records are ignored. */
    private ConsumerRecord<String, String> awaitRecord(String topic, Predicate<String> matches) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : batch.records(topic)) {
                    if (matches.test(record.value())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record arrived on " + topic + " within " + PATIENCE);
    }

    @Test
    void anOrderBecomesARecordOnKafkaAndThenARowInTheEventLog() throws Exception {
        long orderId = createOrder();
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);
        String eventId = outbox.findByAggregateTypeAndAggregateIdOrderByOccurredAtAsc("order",
                Long.toString(orderId)).getFirst().getId().toString();

        assertThat(relay.relayOnce()).isEqualTo(1);

        // The relay stamps what it sent, so the next run does not send it again.
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        assertThat(outbox.countByPublishedAtIsNotNull()).isEqualTo(1);
        assertThat(relay.relayOnce()).isZero();

        // What is actually on the wire: the envelope, keyed by the aggregate id.
        ConsumerRecord<String, String> record =
                awaitRecord(EventType.Topics.ORDER_CREATED, value -> value.contains(eventId));
        assertThat(record.key()).isEqualTo(Long.toString(orderId));
        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(envelope.get("type").asString()).isEqualTo("ORDER_CREATED");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("aggregateType").asString()).isEqualTo("order");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(Long.toString(orderId));
        assertThat(envelope.get("occurredAt").asString()).isNotBlank();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();
        assertThat(envelope.get("payload").get("orderId").asLong()).isEqualTo(orderId);

        // And the first consumer has recorded it.
        await().atMost(PATIENCE).until(() -> recordedEvents() == 1);
        getUrl("/api/events?aggregateType=order&aggregateId=" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].eventId").value(eventId))
                .andExpect(jsonPath("$.content[0].eventType").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.content[0].summary").value(
                        Matchers.containsString("created at warehouse " + warehouseId)))
                .andExpect(jsonPath("$.content[0].correlationId").isNotEmpty());
    }

    @Test
    void theScheduledRunStampsWhatItSentInsteadOfResendingIt() throws Exception {
        // The timer's path, not just the relay's: with the schedule on the relay itself, the self-call ran
        // outside the transaction, published_at was never written, and every run re-sent the same events.
        createOrder();

        scheduler.publishPending();

        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        assertThat(outbox.countByPublishedAtIsNotNull()).isEqualTo(1);
        await().atMost(PATIENCE).until(() -> recordedEvents() == 1);
    }

    @Test
    void theSameRecordDeliveredTwiceIsRecordedOnce() {
        // At-least-once delivery in the open: the relay re-sends if it crashed between send and stamp.
        String eventId = UUID.randomUUID().toString();
        String envelope = """
                {"eventId":"%s","type":"ORDER_CREATED","version":1,"aggregateType":"order","aggregateId":"501",\
                "occurredAt":"%s","correlationId":"trace-dup","payload":{"orderId":501,"code":"ORD-501",\
                "warehouseId":1,"priority":"NORMAL"}}""".formatted(eventId, Instant.now());

        kafka.send(EventType.Topics.ORDER_CREATED, "501", envelope);
        kafka.send(EventType.Topics.ORDER_CREATED, "501", envelope);

        await().atMost(PATIENCE).until(() -> recordedEvents() == 1);
        // Hold for a moment so the second delivery is handled (and skipped) before asserting nothing changed.
        await().during(Duration.ofSeconds(3)).atMost(PATIENCE).until(() -> recordedEvents() == 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?::uuid",
                Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void anEventNoConsumerUnderstandsGoesToTheDeadLetterTopic() {
        // An unknown type can never succeed, so it must not block the partition: straight to the DLT.
        String eventId = UUID.randomUUID().toString();
        String envelope = """
                {"eventId":"%s","type":"ORDER_TELEPORTED","version":1,"aggregateType":"order",\
                "aggregateId":"777","occurredAt":"%s","correlationId":null,"payload":{}}"""
                .formatted(eventId, Instant.now());

        kafka.send(EventType.Topics.ORDER_CREATED, "777", envelope);

        ConsumerRecord<String, String> dead =
                awaitRecord(EventType.Topics.ORDER_CREATED + ".DLT", value -> value.contains(eventId));
        assertThat(dead.value()).contains("ORDER_TELEPORTED");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(recordedEvents()).isZero();
    }

    @Test
    void laterEventsAboutOneOrderAreNotHeldUpByAnEarlierBadRecord() {
        // The reason a poison record is dead-lettered rather than retried forever.
        String poison = """
                {"eventId":"%s","type":"not even an event","version":1,"aggregateType":"order",\
                "aggregateId":"778","occurredAt":"%s","correlationId":null,"payload":{}}"""
                .formatted(UUID.randomUUID(), Instant.now());
        String good = """
                {"eventId":"%s","type":"ORDER_CANCELLED","version":1,"aggregateType":"order",\
                "aggregateId":"778","occurredAt":"%s","correlationId":null,\
                "payload":{"orderId":778,"code":"ORD-778","reason":"Customer cancelled"}}"""
                .formatted(UUID.randomUUID(), Instant.now());

        kafka.send(EventType.Topics.DELIVERY_COMPLETED, "778", poison);
        kafka.send(EventType.Topics.DELIVERY_COMPLETED, "778", good);

        await().atMost(PATIENCE).until(() -> recordedEvents() == 1);
        assertThat(jdbc.queryForObject("SELECT summary FROM system_event", String.class))
                .isEqualTo("Order ORD-778 cancelled: Customer cancelled");
    }

    @Test
    void theWholeLifecycleOfOneOrderIsPublishedInOrder() throws Exception {
        long vehicleId = createVehicle("KA01-K-0001", "VAN");
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

        relay.relayOnce();

        // Four order events (IN_TRANSIT is not published) plus the driver's location update.
        await().atMost(PATIENCE).until(() -> recordedEvents() == 5);
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM system_event WHERE aggregate_type = 'order' AND aggregate_id = ?
                ORDER BY occurred_at, id""", String.class, Long.toString(orderId)))
                .containsExactly("ORDER_CREATED", "ORDER_ASSIGNED", "DELIVERY_STARTED", "DELIVERY_COMPLETED");
        getUrl("/api/events?eventType=DELIVERY_COMPLETED").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void theApplicationCreatesItsTopicsWithTheIntendedPartitionCount() throws Exception {
        // The brokers here do not auto-create topics, so this is what makes publishing possible at all.
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()))) {
            List<String> expected = new java.util.ArrayList<>();
            for (String topic : EventType.Topics.ALL) {
                expected.add(topic);
                expected.add(topic + ".DLT");
            }
            Map<String, TopicDescription> described = admin.describeTopics(expected).allTopicNames().get();

            assertThat(described.keySet()).containsExactlyInAnyOrderElementsOf(expected);
            for (String topic : EventType.Topics.ALL) {
                assertThat(described.get(topic).partitions()).hasSize(3);
                assertThat(described.get(topic + ".DLT").partitions()).hasSize(1);
            }
        }
    }

    @Test
    void aLocationEventReachesTheLivePositionReadModel() throws Exception {
        // The second consumer group: the same records that fill the event log also drive the live map.
        long driverId = createDriver(warehouseId, createVehicle("KA01-K-0002", "VAN"));
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        kafka.send(EventType.Topics.DRIVER_LOCATION, Long.toString(driverId), """
                {"eventId":"%s","type":"DRIVER_LOCATION_UPDATED","version":1,"aggregateType":"driver",\
                "aggregateId":"%d","occurredAt":"%s","correlationId":null,\
                "payload":{"driverId":%d,"latitude":12.955,"longitude":77.605,"at":"%s","source":"SIMULATION"}}"""
                .formatted(UUID.randomUUID(), driverId, at, driverId, at));

        await().atMost(PATIENCE).until(() -> positions.of(driverId).isPresent());

        LivePosition position = positions.of(driverId).orElseThrow();
        assertThat(position.latitude()).isEqualTo(12.955);
        assertThat(position.at()).isEqualTo(at);
        assertThat(position.source()).isEqualTo(LocationSource.SIMULATION);
        getUrl("/api/tracking/drivers/" + driverId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SIMULATION"));
    }

    @Test
    void anOlderLocationEventDoesNotMoveTheDriverBackwards() throws Exception {
        long driverId = createDriver(warehouseId, createVehicle("KA01-K-0003", "VAN"));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        send(driverId, 12.900, now);
        await().atMost(PATIENCE).until(() -> positions.of(driverId).isPresent());

        send(driverId, 13.500, now.minusSeconds(60));

        // Nothing to await on (the point is that nothing changes), so hold briefly and then assert.
        await().during(Duration.ofSeconds(3)).atMost(PATIENCE)
                .until(() -> positions.of(driverId).orElseThrow().latitude() == 12.900);
    }

    private void send(long driverId, double latitude, Instant at) {
        kafka.send(EventType.Topics.DRIVER_LOCATION, Long.toString(driverId), """
                {"eventId":"%s","type":"DRIVER_LOCATION_UPDATED","version":1,"aggregateType":"driver",\
                "aggregateId":"%d","occurredAt":"%s","correlationId":null,\
                "payload":{"driverId":%d,"latitude":%s,"longitude":77.605,"at":"%s","source":"API"}}"""
                .formatted(UUID.randomUUID(), driverId, at, driverId, latitude, at));
    }

    @Test
    void theEventStreamIsReadableByStaffAndViewersOnly() throws Exception {
        getUrl("/api/events", users.token(Role.VIEWER)).andExpect(status().isOk());
        getUrl("/api/events", users.token(Role.DISPATCHER)).andExpect(status().isOk());
        getUrl("/api/events", users.driverToken(createDriver(warehouseId, null))).andExpect(status().isForbidden());
        getUrl("/api/events", null).andExpect(status().isUnauthorized());
    }
}
