package com.smartroute.events;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer-side exactly-once handling, which is what makes at-least-once delivery safe.
 *
 * <p>These tests call {@link EventConsumers#handleOnce} directly instead of going through Kafka: a repeated
 * delivery is exactly what they need to produce, and sending the same record twice through a broker would
 * only make that harder to arrange. The Kafka path itself is covered by {@code KafkaEventFlowTest}.
 */
class EventConsumersIdempotencyTest extends ApiTestSupport {

    private static final String GROUP = "test-group";

    @Autowired
    private EventConsumers consumers;

    @Autowired
    private JdbcTemplate jdbc;

    private static EventEnvelope envelope(UUID id) {
        return new EventEnvelope(id.toString(), EventType.ORDER_CREATED, 1, "order", "1",
                Instant.parse("2026-10-06T10:00:00Z"), "trace-1", Map.of("orderId", 1));
    }

    /** Writes a row the test can count, standing in for whatever real work a consumer does. */
    private void doWork(EventEnvelope envelope) {
        jdbc.update("INSERT INTO system_event (event_id, event_type, event_version, topic, aggregate_type,"
                        + " aggregate_id, summary, payload, occurred_at) VALUES (?, ?, 1, ?, ?, ?, ?, '{}'::jsonb, ?)",
                UUID.fromString(envelope.eventId()), envelope.type().name(), envelope.type().topic(),
                envelope.aggregateType(), envelope.aggregateId(), "work for " + envelope.eventId(),
                java.sql.Timestamp.from(envelope.occurredAt()));
    }

    private int workDone() {
        return jdbc.queryForObject("SELECT count(*) FROM system_event", Integer.class);
    }

    @Test
    void theSecondDeliveryOfAnEventDoesNoWork() {
        EventEnvelope event = envelope(UUID.randomUUID());

        assertThat(consumers.handleOnce(GROUP, event, this::doWork)).isTrue();
        assertThat(consumers.handleOnce(GROUP, event, this::doWork)).isFalse();

        assertThat(workDone()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer_group = ?",
                Integer.class, GROUP)).isEqualTo(1);
    }

    @Test
    void anotherConsumerGroupHandlesTheSameEventOnItsOwn() {
        // The marker is per group, so adding a consumer does not mean re-reading someone else's "done" flags.
        EventEnvelope event = envelope(UUID.randomUUID());

        assertThat(consumers.handleOnce(GROUP, event, e -> { })).isTrue();
        assertThat(consumers.handleOnce("other-group", event, e -> { })).isTrue();
    }

    @Test
    void twoThreadsDeliveringAtOnceStillDoTheWorkOnce() throws Exception {
        // The claim is an insert inside the work's transaction, so the race is settled by the primary key
        // rather than by a check-then-act that both threads could pass.
        EventEnvelope event = envelope(UUID.randomUUID());
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    if (consumers.handleOnce(GROUP, event, this::doWork)) {
                        handled.incrementAndGet();
                    } else {
                        duplicates.incrementAndGet();
                    }
                } catch (RuntimeException concurrentInsert) {
                    // A lock-wait loser surfaces as a concurrency failure; it did no work either.
                    duplicates.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(handled.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
        assertThat(workDone()).isEqualTo(1);
    }

    @Test
    void aFailingConsumerLeavesNoClaimBehindSoTheRetryCanWork() {
        EventEnvelope event = envelope(UUID.randomUUID());

        assertThatThrownBy(() -> consumers.handleOnce(GROUP, event, e -> {
            throw new IllegalStateException("downstream is down");
        })).isInstanceOf(IllegalStateException.class);

        // Claim and work share one transaction, so a failure rolls back both: the retry is not a "duplicate".
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isZero();
        assertThat(consumers.handleOnce(GROUP, event, this::doWork)).isTrue();
        assertThat(workDone()).isEqualTo(1);
    }
}
