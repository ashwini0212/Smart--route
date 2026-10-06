package com.smartroute.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The handful of numbers worth alerting on, as gauges.
 *
 * <p>They are <strong>sampled</strong>, not live: one query per metric every 30 seconds, into an
 * {@link AtomicLong} that the gauge reads. A gauge that ran its query on every scrape would put the scrape
 * interval of every monitoring system in charge of this database's load, which is how a metrics endpoint
 * becomes the thing that takes the service down.
 *
 * <p>The counters and timers elsewhere (events consumed, delay alerts, SSE clients, HTTP requests) are
 * incremented where the work happens and need nothing here.
 */
@Component
public class DomainMetrics {

    private static final Logger log = LoggerFactory.getLogger(DomainMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong waitingOrders = new AtomicLong();
    private final AtomicLong activeDeliveries = new AtomicLong();
    private final AtomicLong availableDrivers = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong oldestOutboxAgeSeconds = new AtomicLong();

    DomainMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        gauge(meters, "smartroute.orders.waiting", "Orders with no driver yet", waitingOrders);
        gauge(meters, "smartroute.deliveries.active", "Deliveries under way", activeDeliveries);
        gauge(meters, "smartroute.drivers.available", "Drivers on shift and able to take an order", availableDrivers);
        gauge(meters, "smartroute.outbox.pending", "Events written but not yet published", outboxPending);
        gauge(meters, "smartroute.outbox.oldest.age", "Age of the oldest unpublished event, in seconds",
                oldestOutboxAgeSeconds);
        sample();
    }

    private static void gauge(MeterRegistry meters, String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue).description(description).register(meters);
    }

    /**
     * Refreshes every gauge.
     *
     * <p>A failure here must not stop the scheduler or fill the log: if the database is unavailable the metrics
     * keep their last value, which the alert on {@code smartroute.outbox.oldest.age} will notice soon enough.
     */
    @Scheduled(fixedDelayString = "${smartroute.metrics.sample-interval:30s}")
    public void sample() {
        try {
            waitingOrders.set(count("SELECT count(*) FROM delivery_order WHERE status = 'CREATED'"));
            activeDeliveries.set(count(
                    "SELECT count(*) FROM delivery_order WHERE status IN ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT')"));
            availableDrivers.set(count("SELECT count(*) FROM driver WHERE status IN ('AVAILABLE', 'ON_DELIVERY')"));
            outboxPending.set(count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL"));
            oldestOutboxAgeSeconds.set(count(
                    "SELECT coalesce(EXTRACT(EPOCH FROM (now() - min(created_at)))::bigint, 0)"
                            + " FROM outbox_event WHERE published_at IS NULL"));
        } catch (RuntimeException e) {
            log.debug("Could not refresh domain metrics: {}", e.toString());
        }
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
