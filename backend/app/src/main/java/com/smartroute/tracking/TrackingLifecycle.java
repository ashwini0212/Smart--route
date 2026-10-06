package com.smartroute.tracking;

import com.smartroute.order.OrderStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Clears a delivery's delay alert when the delivery stops being active (delivered, failed, cancelled or
 * taken off the driver).
 *
 * <p>Without this the row would outlive the delivery: the sweep only looks at orders a driver currently
 * holds, so nothing else would ever remove it. The listener is synchronous, so the alert disappears in the
 * same transaction as the status change.
 */
@Component
class TrackingLifecycle {

    private final DeliveryAlertRepository alerts;

    TrackingLifecycle(DeliveryAlertRepository alerts) {
        this.alerts = alerts;
    }

    @EventListener
    void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (!event.current().isActive()) {
            alerts.deleteByOrderIdIn(List.of(event.orderId()));
        }
    }
}
