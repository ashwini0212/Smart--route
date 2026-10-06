package com.smartroute.events;

import com.smartroute.fleet.DriverLocationChangedEvent;
import com.smartroute.order.OrderStatus;
import com.smartroute.order.OrderStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns in-process domain events into outbox rows.
 *
 * <p>Listeners are synchronous, so the outbox row is written inside the same transaction as the status change
 * (see {@link DomainEvents}). The modules that change orders and drivers know nothing about Kafka; they
 * publish a Spring event and this class decides what, if anything, goes on the wire.
 *
 * <p>This runs even when {@code smartroute.events.enabled} is false: the outbox is part of the domain
 * transaction, and the switch only decides whether anything is shipped to Kafka.
 */
@Component
class OrderEventPublisher {

    static final String ORDER = "order";
    static final String DRIVER = "driver";

    private final DomainEvents events;

    OrderEventPublisher(DomainEvents events) {
        this.events = events;
    }

    @EventListener
    void onOrderStatusChanged(OrderStatusChangedEvent event) {
        EventType type = typeFor(event);
        if (type == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", event.orderId());
        payload.put("code", event.code());
        payload.put("warehouseId", event.warehouseId());
        payload.put("priority", event.priority().name());
        payload.put("status", event.current().name());
        payload.put("previousStatus", event.previous() == null ? null : event.previous().name());
        payload.put("driverId", event.driverId());
        payload.put("weightKg", event.weightKg());
        payload.put("volumeM3", event.volumeM3());
        payload.put("reason", event.reason());
        payload.put("at", event.at().toString());
        events.append(type, ORDER, Long.toString(event.orderId()), payload);
    }

    /**
     * One status change maps to one event type. CREATED → ASSIGNED and ASSIGNED → CREATED are different
     * events even though both involve the same two statuses, which is why the previous status decides.
     */
    private static EventType typeFor(OrderStatusChangedEvent event) {
        return switch (event.current()) {
            case CREATED -> event.previous() == null ? EventType.ORDER_CREATED : EventType.ORDER_UNASSIGNED;
            case ASSIGNED -> EventType.ORDER_ASSIGNED;
            case PICKED_UP -> EventType.DELIVERY_STARTED;
            case DELIVERED -> EventType.DELIVERY_COMPLETED;
            case FAILED -> EventType.DELIVERY_FAILED;
            case CANCELLED -> EventType.ORDER_CANCELLED;
            // IN_TRANSIT is a step within a delivery, not something another system acts on.
            case IN_TRANSIT -> null;
        };
    }

    @EventListener
    void onDriverMoved(DriverLocationChangedEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", event.driverId());
        payload.put("latitude", event.latitude());
        payload.put("longitude", event.longitude());
        payload.put("at", event.at().toString());
        events.append(EventType.DRIVER_LOCATION_UPDATED, DRIVER, Long.toString(event.driverId()), payload);
    }

    /** Kept explicit so the mapping above stays exhaustive when a status is added. */
    static boolean publishes(OrderStatus status) {
        return status != OrderStatus.IN_TRANSIT;
    }
}
