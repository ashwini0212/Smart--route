package com.smartroute.assignment;

import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.DriverStatus;
import com.smartroute.fleet.DriverStatusChangedEvent;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderService;
import com.smartroute.order.OrderStatus;
import com.smartroute.order.OrderStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps drivers and orders consistent. Both listeners are synchronous: they run inside the transaction
 * that changed the order or driver, so the two sides commit or roll back together.
 */
@Component
class AssignmentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AssignmentLifecycle.class);

    private final DriverService drivers;
    private final OrderService orders;

    AssignmentLifecycle(DriverService drivers, OrderService orders) {
        this.drivers = drivers;
        this.orders = orders;
    }

    /** Delivered, failed, cancelled or unassigned: the order no longer takes space on the vehicle. */
    @EventListener
    void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.leftDriver()) {
            drivers.releaseCapacity(event.driverId(), event.weightKg(), event.volumeM3());
        }
    }

    /**
     * A driver going OFFLINE can't collect orders that are only ASSIGNED: they go back to the queue with the
     * reason in their history. Orders already PICKED_UP or IN_TRANSIT are physically in the vehicle, so they
     * stay with the driver (a dispatcher must handle them) and a warning is logged.
     */
    @EventListener
    void onDriverStatusChanged(DriverStatusChangedEvent event) {
        if (event.current() != DriverStatus.OFFLINE) {
            return;
        }
        for (OrderResponse order : orders.activeForDriver(event.driverId())) {
            if (order.status() == OrderStatus.ASSIGNED) {
                orders.unassign(order.id(), "Driver " + event.driverCode() + " went offline");
            } else {
                log.warn("Driver {} went offline while carrying order {} ({})", event.driverCode(), order.code(),
                        order.status());
            }
        }
    }
}
