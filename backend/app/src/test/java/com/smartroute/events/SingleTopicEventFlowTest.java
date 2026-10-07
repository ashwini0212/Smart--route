package com.smartroute.events;

import com.smartroute.support.ApiTestSupport;
import com.smartroute.support.KafkaTestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one-topic layout used on the free managed Kafka (see {@link EventTopics}) on a real broker: the
 * application creates only that topic and its dead-letter topic, events of different types share it, and the
 * event-log consumer still records each of them.
 */
@SpringBootTest(properties = {
        "smartroute.events.enabled=true",
        "smartroute.events.relay-interval=1h",
        "smartroute.events.single-topic=smartroute.events",
        "smartroute.events.topic-partitions=2"})
@Import(KafkaTestcontainersConfiguration.class)
class SingleTopicEventFlowTest extends ApiTestSupport {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Test
    void everyEventTravelsOnTheOneTopicAndIsStillConsumed() throws Exception {
        long warehouseId = createWarehouse("WH-ONE-TOPIC");
        long orderId = body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":12.99,"dropLongitude":77.62,"priority":"NORMAL","weightKg":10,"volumeM3":0.5}
                """.formatted(warehouseId)).andExpect(status().isCreated())).get("id").asLong();
        postJson("/api/orders/" + orderId + "/cancel", "{\"reason\":\"test\"}").andExpect(status().isOk());

        assertThat(relay.relayOnce()).isEqualTo(2);

        // Two event types that have different topics in the default layout, both on the one topic, keyed
        // by the order so they share a partition and keep their order.
        List<ConsumerRecord<String, String>> records = readAll("smartroute.events", 2);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(Long.toString(orderId));
        assertThat(records.get(0).value()).contains("\"type\":\"ORDER_CREATED\"");
        assertThat(records.get(1).value()).contains("\"type\":\"ORDER_CANCELLED\"");

        await().atMost(PATIENCE).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM system_event", Long.class) == 2);

        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()))) {
            Set<String> names = admin.listTopics().names().get();
            assertThat(names).contains("smartroute.events", "smartroute.events.DLT")
                    .doesNotContain(EventType.Topics.ORDER_CREATED, EventType.Topics.DELIVERY_COMPLETED);
            assertThat(admin.describeTopics(List.of("smartroute.events")).allTopicNames().get()
                    .get("smartroute.events").partitions()).hasSize(2);
        }
    }

    private List<ConsumerRecord<String, String>> readAll(String topic, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).records(topic).forEach(records::add);
            }
            return records;
        }
    }
}
