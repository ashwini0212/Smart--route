package com.smartroute.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka, oldest first, and stamps them as published.
 *
 * <p>Ordering: rows are read in creation order and each send is confirmed before the next one starts, so
 * events about one aggregate reach their partition in the order they happened. That costs throughput (one
 * round trip per event) and buys an ordering guarantee the system actually relies on: "assigned" must not
 * arrive before "created".
 *
 * <p>Failure: a send that fails leaves {@code published_at} null with the error recorded, so the next run
 * retries it. A crash between a successful send and the stamp re-sends the event, which is why every
 * consumer deduplicates on {@code eventId} ({@link ProcessedEvents}). That is at-least-once delivery, the
 * standard trade of an outbox: no event is lost, some may be delivered twice.
 */
@Component
@ConditionalOnProperty(name = "smartroute.events.enabled", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** Rows per run. Small enough that one slow Kafka does not hold a transaction open for long. */
    static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.published = Counter.builder("smartroute.events.published").register(meters);
        this.failed = Counter.builder("smartroute.events.publish.failed").register(meters);
    }

    /**
     * Publishes up to one batch and stamps what was sent; returns how many were published.
     *
     * <p>Called by {@link OutboxRelayScheduler} rather than by a scheduled method on this class: a
     * {@code @Scheduled} method calling this one directly would bypass the proxy, run with no transaction,
     * and leave {@code published_at} unset, so the relay would re-send the same events every run.
     */
    @Transactional
    public int relayOnce() {
        List<OutboxEvent> pending = outbox.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(BATCH_SIZE));
        int sent = 0;
        for (OutboxEvent event : pending) {
            try {
                // Each send is awaited: ordering per aggregate matters more here than throughput.
                kafka.send(event.getTopic(), event.getPartitionKey(), envelopeJson(event))
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markPublished(clock.instant());
                published.increment();
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.markFailed("interrupted");
                break;
            } catch (Exception e) {
                // Leave published_at null: the next run tries again. Stop the batch so order is preserved.
                event.markFailed(e.getMessage());
                failed.increment();
                log.warn("Could not publish event {} to {}: {}", event.getId(), event.getTopic(), e.toString());
                break;
            }
        }
        return sent;
    }

    /**
     * The envelope is assembled from the stored columns rather than kept as one JSON blob, so a schema
     * change (a new envelope field) does not require rewriting old rows.
     */
    private static String envelopeJson(OutboxEvent event) {
        return """
                {"eventId":"%s","type":"%s","version":%d,"aggregateType":"%s","aggregateId":"%s",\
                "occurredAt":"%s","correlationId":%s,"payload":%s}"""
                .formatted(event.getId(), event.getEventType(), event.getEventVersion(), event.getAggregateType(),
                        event.getAggregateId(), event.getOccurredAt(),
                        event.getCorrelationId() == null ? "null" : "\"" + event.getCorrelationId() + "\"",
                        event.getPayload());
    }
}
