package com.smartroute.events;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumer-side deduplication.
 *
 * <p>Delivery is at-least-once, so a consumer can see the same event twice (the relay re-sent it after a
 * crash, or Kafka re-delivered after a rebalance). Instead of asking "have I seen this?" and then acting —
 * two steps another consumer instance can interleave — the consumer <em>inserts</em> the (group, event) row
 * in the same transaction as its work. The primary key makes the second insert fail, so exactly one delivery
 * does the work.
 *
 * <p>The duplicate shows up as a constraint violation, which aborts the transaction. That is the desired
 * outcome: the repeated work is rolled back together with the marker, leaving the first delivery's result
 * untouched. The exception must therefore cross the transaction boundary before being caught — see
 * {@link EventConsumers#handleOnce}.
 */
@Service
public class ProcessedEvents {

    private final ProcessedEventRepository repository;

    ProcessedEvents(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /** Claims the event for this consumer group; throws if the group already handled it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claim(String consumerGroup, UUID eventId) {
        repository.saveAndFlush(new ProcessedEvent(consumerGroup, eventId));
    }

    static boolean isDuplicate(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataIntegrityViolationException) {
                return true;
            }
        }
        return false;
    }
}
