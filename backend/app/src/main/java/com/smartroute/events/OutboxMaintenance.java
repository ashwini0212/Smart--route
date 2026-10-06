package com.smartroute.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes published outbox rows past the retention window, hourly.
 *
 * <p>Published rows are kept for a while on purpose: they are the record of exactly what was sent, which is
 * what you want when a consumer claims it never received something. Unpublished rows are never deleted.
 */
@Component
@ConditionalOnProperty(name = "smartroute.events.enabled", matchIfMissing = true)
class OutboxMaintenance {

    private static final Logger log = LoggerFactory.getLogger(OutboxMaintenance.class);

    private final EventStreamService events;

    OutboxMaintenance(EventStreamService events) {
        this.events = events;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    void deleteOldPublishedEvents() {
        long deleted = events.deletePublishedOlderThanRetention();
        if (deleted > 0) {
            log.info("Deleted {} published outbox rows past the retention window", deleted);
        }
    }
}
