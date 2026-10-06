package com.smartroute.tracking;

import com.smartroute.assignment.DeliveryRouteService;
import com.smartroute.events.DomainEvents;
import com.smartroute.events.EventType;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderService;
import com.smartroute.routing.OptimizedRoute;
import com.smartroute.routing.RouteMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Watches the deliveries that are under way: who is going to be late, and whose route has changed.
 *
 * <p>Both answers come from the Phase 8 sequencer. For each driver with active deliveries the sweep asks
 * {@link DeliveryRouteService} for the route from the driver's current position, which already returns the
 * arrival time per stop and the stops that miss their window. So "predicted to be late" here means
 * <em>on the current road network, from where the driver is now, visiting their remaining drops in the
 * computed order</em> — not a statistical forecast. It inherits that order's caveats: above 12 stops the
 * order is a heuristic, and nothing models how long a driver spends looking for a doorbell beyond the flat
 * service time.
 *
 * <p>Two kinds of noise are deliberately suppressed, because an alert nobody can act on is worse than none:
 * a delivery must be predicted to arrive more than {@code lateThreshold} past its window before it is
 * reported, and an already-reported delay is only reported again once it has grown by {@code lateGrowth}.
 * A route counts as recalculated when its visiting order changes, or its duration moves by more than
 * {@code recalculateShift}.
 */
@Service
@EnableConfigurationProperties(TrackingProperties.class)
public class DeliveryWatch {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWatch.class);

    private final OrderService orders;
    private final DeliveryRouteService routes;
    private final DeliveryAlertRepository alerts;
    private final DriverRouteSnapshotRepository snapshots;
    private final DomainEvents events;
    private final TransactionTemplate transactions;
    private final TrackingProperties properties;
    private final Clock clock;
    private final Counter delaysReported;
    private final Counter recalculations;
    private final Counter sweepFailures;

    DeliveryWatch(OrderService orders, DeliveryRouteService routes, DeliveryAlertRepository alerts,
                  DriverRouteSnapshotRepository snapshots, DomainEvents events, TransactionTemplate transactions,
                  TrackingProperties properties, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.routes = routes;
        this.alerts = alerts;
        this.snapshots = snapshots;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.delaysReported = Counter.builder("smartroute.tracking.delays.reported").register(meters);
        this.recalculations = Counter.builder("smartroute.tracking.routes.recalculated").register(meters);
        this.sweepFailures = Counter.builder("smartroute.tracking.sweep.failures").register(meters);
    }

    /** What one sweep found, returned so a caller (and the tests) can see it without reading the log. */
    public record SweepResult(int driversChecked, int delaysReported, int routesRecalculated, int failures,
                              long durationMillis) {
    }

    /**
     * Checks every driver with active deliveries.
     *
     * <p>One driver per transaction: a failure for one driver (no position yet, a drop off the network) must
     * not discard what was learned about the others, and a long sweep must not hold one transaction open.
     */
    public SweepResult sweep(String reason) {
        long started = System.nanoTime();
        List<Long> drivers = orders.driversWithActiveDeliveries();
        int delays = 0;
        int recalculated = 0;
        int failures = 0;
        for (long driverId : drivers) {
            try {
                DriverOutcome outcome = transactions.execute(status -> checkDriver(driverId, reason));
                delays += outcome.delays();
                recalculated += outcome.recalculated() ? 1 : 0;
            } catch (RuntimeException e) {
                // Expected for a driver with no position yet, or a drop that cannot be routed to.
                failures++;
                sweepFailures.increment();
                log.debug("Could not check driver {}: {}", driverId, e.toString());
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (delays > 0 || recalculated > 0) {
            log.info("Tracking sweep ({}): {} drivers, {} delay alerts, {} routes recalculated, {} skipped, {} ms",
                    reason, drivers.size(), delays, recalculated, failures, millis);
        }
        return new SweepResult(drivers.size(), delays, recalculated, failures, millis);
    }

    private record DriverOutcome(int delays, boolean recalculated) {
    }

    private DriverOutcome checkDriver(long driverId, String reason) {
        List<OrderResponse> deliveries = orders.activeForDriver(driverId);
        if (deliveries.isEmpty()) {
            return new DriverOutcome(0, false);
        }
        Instant now = clock.instant();
        OptimizedRoute route = routes.forDriver(driverId, RouteMode.FASTEST, null, now);
        int delays = reportDelays(driverId, deliveries, route, now);
        boolean recalculated = recordRoute(driverId, route, reason, now);
        return new DriverOutcome(delays, recalculated);
    }

    /** Raises an alert for every stop that misses its window by more than the threshold, and clears the rest. */
    private int reportDelays(long driverId, List<OrderResponse> deliveries, OptimizedRoute route, Instant now) {
        Map<String, OrderResponse> byCode = new HashMap<>();
        deliveries.forEach(order -> byCode.put(order.code(), order));
        Map<Long, DeliveryAlert> reported = new HashMap<>();
        alerts.findByOrderIdIn(byCode.values().stream().map(OrderResponse::id).toList())
                .forEach(alert -> reported.put(alert.getOrderId(), alert));

        List<Long> noLongerLate = new ArrayList<>(reported.keySet());
        int raised = 0;
        for (OptimizedRoute.Late late : route.lateStops()) {
            OrderResponse order = byCode.get(late.label());
            if (order == null || late.lateBySeconds() < properties.lateThreshold().toSeconds()) {
                continue;
            }
            noLongerLate.remove(order.id());
            DeliveryAlert alert = reported.get(order.id());
            long growth = alert == null ? Long.MAX_VALUE : late.lateBySeconds() - alert.getLateBySeconds();
            if (alert != null && growth < properties.lateGrowth().toSeconds()) {
                continue;
            }
            publishDelay(order, driverId, late, route, now);
            if (alert == null) {
                alerts.save(new DeliveryAlert(order.id(), (int) late.lateBySeconds(), now));
            } else {
                alert.update((int) late.lateBySeconds(), now);
                alerts.save(alert);
            }
            raised++;
        }
        // A delivery that caught up (traffic cleared, a stop was removed) can be alerted again if it slips back.
        if (!noLongerLate.isEmpty()) {
            alerts.deleteByOrderIdIn(noLongerLate);
        }
        return raised;
    }

    private void publishDelay(OrderResponse order, long driverId, OptimizedRoute.Late late, OptimizedRoute route,
                              Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("code", order.code());
        payload.put("driverId", driverId);
        payload.put("lateBySeconds", late.lateBySeconds());
        payload.put("dueBy", late.dueBy().toString());
        payload.put("predictedArrival", late.arriveAt().toString());
        payload.put("stopsRemaining", route.stopCount());
        payload.put("sequenceAlgorithm", route.algorithm());
        payload.put("graphVersion", route.graphVersion());
        payload.put("predictedAt", now.toString());
        events.append(EventType.DELIVERY_DELAYED, "order", Long.toString(order.id()), payload);
        delaysReported.increment();
    }

    /**
     * Stores the route and publishes {@code ROUTE_RECALCULATED} when it differs from the stored one.
     *
     * @return true when an event was published
     */
    private boolean recordRoute(long driverId, OptimizedRoute route, String reason, Instant now) {
        String sequence = String.join(">", route.visits().stream().map(OptimizedRoute.Visit::label).toList());
        Optional<DriverRouteSnapshot> previous = snapshots.findById(driverId);
        if (previous.isEmpty()) {
            snapshots.save(new DriverRouteSnapshot(driverId, route.graphVersion(), route.totalDurationSeconds(),
                    sequence, now));
            return false;
        }
        DriverRouteSnapshot snapshot = previous.get();
        double before = snapshot.getDurationSeconds();
        boolean orderChanged = !snapshot.getStopSequence().equals(truncate(sequence));
        boolean durationMoved = before > 0
                && Math.abs(route.totalDurationSeconds() - before) / before > properties.recalculateShift();
        snapshot.update(route.graphVersion(), route.totalDurationSeconds(), sequence, now);
        snapshots.save(snapshot);
        if (!orderChanged && !durationMoved) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driverId);
        payload.put("reason", reason);
        payload.put("previousDurationSeconds", Math.round(before));
        payload.put("durationSeconds", Math.round(route.totalDurationSeconds()));
        payload.put("visitingOrderChanged", orderChanged);
        payload.put("stopSequence", truncate(sequence));
        payload.put("graphVersion", route.graphVersion());
        payload.put("algorithm", route.algorithm());
        events.append(EventType.ROUTE_RECALCULATED, "driver", Long.toString(driverId), payload);
        recalculations.increment();
        return true;
    }

    private static String truncate(String sequence) {
        return sequence.length() > DriverRouteSnapshot.MAX_SEQUENCE_LENGTH
                ? sequence.substring(0, DriverRouteSnapshot.MAX_SEQUENCE_LENGTH) : sequence;
    }
}
