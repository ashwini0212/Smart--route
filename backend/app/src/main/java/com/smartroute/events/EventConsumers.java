package com.smartroute.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Runs a consumer's work exactly once per event, whatever Kafka delivers.
 *
 * <p>The claim and the work share one transaction. A repeated delivery fails the claim's primary key, the
 * transaction rolls back (so the repeated work leaves no trace), and the exception is caught here and
 * reported as a skipped duplicate — the message is then acknowledged, because re-delivering it again would
 * change nothing.
 *
 * <p>Any other failure is rethrown, which is what triggers the retry and dead-letter handling configured in
 * {@link KafkaConfig}.
 */
@Component
public class EventConsumers {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);

    private final ProcessedEvents processedEvents;
    private final TransactionalRunner runner;
    private final Counter handled;
    private final Counter duplicates;

    EventConsumers(ProcessedEvents processedEvents, TransactionalRunner runner, MeterRegistry meters) {
        this.processedEvents = processedEvents;
        this.runner = runner;
        this.handled = Counter.builder("smartroute.events.consumed").tag("result", "handled").register(meters);
        this.duplicates = Counter.builder("smartroute.events.consumed").tag("result", "duplicate").register(meters);
    }

    /** @return true when the work ran, false when this event had already been handled by the group */
    public boolean handleOnce(String consumerGroup, EventEnvelope envelope, Consumer<EventEnvelope> work) {
        try {
            runner.inTransaction(() -> {
                processedEvents.claim(consumerGroup, java.util.UUID.fromString(envelope.eventId()));
                work.accept(envelope);
            });
            handled.increment();
            return true;
        } catch (RuntimeException e) {
            if (ProcessedEvents.isDuplicate(e)) {
                duplicates.increment();
                log.debug("Event {} was already handled by {}, skipping", envelope.eventId(), consumerGroup);
                return false;
            }
            throw e;
        }
    }
}
