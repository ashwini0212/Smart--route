package com.smartroute.assignment;

import com.smartroute.common.error.ApiException;
import com.smartroute.fleet.DriverCandidateView;
import com.smartroute.fleet.DriverService;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderService;
import com.smartroute.routing.GeoPoint;
import com.smartroute.routing.OptimizeRequest;
import com.smartroute.routing.OptimizeStop;
import com.smartroute.routing.OptimizeStrategy;
import com.smartroute.routing.OptimizedRoute;
import com.smartroute.routing.RouteMode;
import com.smartroute.routing.RouteOptimizationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The driving order for one driver's current deliveries: start where the driver is, visit every drop,
 * no return leg (the next assignment decides where they go after).
 *
 * <p>Each order's delivery window end becomes the stop's deadline, so the answer says which drops cannot be
 * made in time instead of quietly planning to be late.
 */
@Service
public class DeliveryRouteService {

    /** Minutes assumed at each drop (hand over, signature). Stated, not measured. */
    static final int SERVICE_MINUTES_PER_STOP = 4;

    private final OrderService orders;
    private final DriverService drivers;
    private final RouteOptimizationService optimizer;

    DeliveryRouteService(OrderService orders, DriverService drivers, RouteOptimizationService optimizer) {
        this.orders = orders;
        this.drivers = drivers;
        this.optimizer = optimizer;
    }

    public OptimizedRoute forDriver(long driverId, RouteMode mode, OptimizeStrategy strategy, Instant departAt) {
        DriverCandidateView driver = drivers.candidates(List.of(driverId)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Driver", driverId));
        if (driver.latitude() == null) {
            throw ApiException.businessRule("Driver " + driver.code() + " has no known position yet");
        }
        List<OrderResponse> deliveries = orders.activeForDriver(driverId);
        if (deliveries.isEmpty()) {
            throw ApiException.businessRule("Driver " + driver.code() + " has no active deliveries");
        }
        List<OptimizeStop> stops = deliveries.stream()
                .map(o -> new OptimizeStop(o.code(), new GeoPoint(o.dropLatitude(), o.dropLongitude()),
                        o.weightKg(), o.volumeM3(), o.windowEnd(), SERVICE_MINUTES_PER_STOP))
                .toList();
        OptimizeRequest request = new OptimizeRequest(new GeoPoint(driver.latitude(), driver.longitude()), stops,
                mode == null ? RouteMode.FASTEST : mode, strategy, false, departAt, null, null);
        return optimizer.optimize(request);
    }
}
