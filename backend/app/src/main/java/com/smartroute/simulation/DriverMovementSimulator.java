package com.smartroute.simulation;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.LocationSource;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderService;
import com.smartroute.routing.GeoPoint;
import com.smartroute.routing.RouteEngine;
import com.smartroute.routing.RouteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * <b>[SIMULATION]</b> Moves drivers that have active deliveries towards their first drop.
 *
 * <p>Nothing here is real: there are no devices, so this invents positions so that the live map, the delay
 * detection and the recalculation logic have something to work on. It is off unless
 * {@code smartroute.simulation.drivers=true}, it logs a warning at startup when it is on, and every position
 * it produces is labelled {@link LocationSource#SIMULATION} all the way into the event payload and the live
 * stream — so nothing downstream can mistake it for a driver with a phone.
 *
 * <p>How it moves: it routes from the driver's current position to the first active drop on the real road
 * graph and walks {@code speedKph} worth of metres along that path per tick, placing the driver at the point
 * it reaches (interpolating inside the last segment). So the driver follows roads rather than flying straight,
 * which is what makes the resulting ETAs worth looking at. What it does <em>not</em> model: acceleration,
 * stopping at lights, parking, the driver taking a different turn, or any position error.
 */
@Component
@ConditionalOnProperty(name = "smartroute.simulation.drivers", havingValue = "true")
@EnableConfigurationProperties(SimulationProperties.class)
public class DriverMovementSimulator {

    private static final Logger log = LoggerFactory.getLogger(DriverMovementSimulator.class);
    /** Close enough to a drop to call it arrived; the driver then waits there for the status change. */
    static final double ARRIVED_METERS = 25;

    private final OrderService orders;
    private final DriverService drivers;
    private final RouteEngine engine;
    private final SimulationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    DriverMovementSimulator(OrderService orders, DriverService drivers, RouteEngine engine,
                            SimulationProperties properties, TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.drivers = drivers;
        this.engine = engine;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        log.warn("[SIMULATION] driver movement simulator is ON: driver positions are synthetic, {} km/h, every {}",
                properties.speedKph(), properties.tick());
    }

    @Scheduled(fixedDelayString = "${smartroute.simulation.tick:2s}")
    public void moveEveryone() {
        for (long driverId : orders.driversWithActiveDeliveries()) {
            try {
                transactions.executeWithoutResult(status -> moveOne(driverId));
            } catch (RuntimeException e) {
                log.debug("[SIMULATION] could not move driver {}: {}", driverId, e.toString());
            }
        }
    }

    /** @return true when the driver was moved */
    public boolean moveOne(long driverId) {
        GeoPoint from = drivers.candidates(List.of(driverId)).stream()
                .filter(d -> d.latitude() != null)
                .map(d -> new GeoPoint(d.latitude(), d.longitude()))
                .findFirst()
                .orElse(null);
        if (from == null) {
            return false;
        }
        List<OrderResponse> deliveries = orders.activeForDriver(driverId);
        if (deliveries.isEmpty()) {
            return false;
        }
        OrderResponse target = deliveries.getFirst();
        GeoPoint drop = new GeoPoint(target.dropLatitude(), target.dropLongitude());
        if (GeoMath.haversineMeters(from.latitude(), from.longitude(), drop.latitude(), drop.longitude())
                <= ARRIVED_METERS) {
            return false;
        }
        RouteEngine.RouteOutcome outcome = engine.route(RouteMode.FASTEST, from, drop);
        GeoPoint next = advance(outcome.route().path(), properties.metersPerTick());
        drivers.updateLocation(driverId, next.latitude(), next.longitude(), clock.instant(),
                LocationSource.SIMULATION);
        return true;
    }

    /**
     * The point {@code meters} along the path, interpolated inside the segment it falls in.
     *
     * <p>Interpolating matters: snapping to the next node instead would make a driver jump a whole block at a
     * time, and on the synthetic grid that is 100 m — bigger than the distances the delay logic cares about.
     */
    static GeoPoint advance(List<double[]> path, double meters) {
        double remaining = meters;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double leg = GeoMath.haversineMeters(a[0], a[1], b[0], b[1]);
            if (leg >= remaining) {
                double fraction = leg == 0 ? 1 : remaining / leg;
                return new GeoPoint(a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction);
            }
            remaining -= leg;
        }
        double[] last = path.getLast();
        return new GeoPoint(last[0], last[1]);
    }
}
