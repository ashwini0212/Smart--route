package com.smartroute.events;

import com.smartroute.common.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * How the rest of the application publishes an event: one call, inside the transaction that made the change.
 *
 * <p>Nothing is sent to Kafka here. The event is written to the outbox table in the same transaction as the
 * business change, which is the point: a direct {@code kafkaTemplate.send} could succeed while the
 * transaction later rolls back (an event about something that never happened), or the transaction could
 * commit while the send fails (a change nobody hears about). Writing both to the same database makes them
 * one atomic fact; {@link OutboxRelay} does the sending afterwards.
 */
@Service
public class DomainEvents {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter appended;

    DomainEvents(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.appended = Counter.builder("smartroute.events.appended").register(meters);
    }

    /**
     * Appends an event to the outbox.
     *
     * @param aggregateType what the event is about ("order", "driver")
     * @param aggregateId   its id; also the Kafka key, so all events about one order land on one partition
     *                      and are therefore delivered in order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(EventType type, String aggregateType, String aggregateId, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        outbox.save(new OutboxEvent(id, type, aggregateType, aggregateId, aggregateId,
                objectMapper.writeValueAsString(payload), CorrelationId.current(), clock.instant()));
        appended.increment();
        return id;
    }
}
