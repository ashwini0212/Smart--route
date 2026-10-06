package com.smartroute.events;

/**
 * Every domain event this system publishes, and the topic it goes to.
 *
 * <p>Topics follow the lifecycle step rather than the aggregate, because that is how consumers subscribe:
 * a notification service wants completions, analytics wants everything. Unassignment shares the
 * {@code order-assigned} topic so that a consumer tracking who has an order sees assignment and
 * unassignment in one stream, in order (both are keyed by the order id).
 *
 * @param version payload schema version. A consumer reads it and may refuse what it does not understand;
 *                it is bumped when a payload changes in a way old consumers cannot handle.
 */
public enum EventType {
    ORDER_CREATED(Topics.ORDER_CREATED, 1),
    ORDER_ASSIGNED(Topics.ORDER_ASSIGNED, 1),
    ORDER_UNASSIGNED(Topics.ORDER_ASSIGNED, 1),
    ORDER_CANCELLED(Topics.DELIVERY_COMPLETED, 1),
    DELIVERY_STARTED(Topics.DELIVERY_STARTED, 1),
    DELIVERY_COMPLETED(Topics.DELIVERY_COMPLETED, 1),
    DELIVERY_FAILED(Topics.DELIVERY_COMPLETED, 1),
    DELIVERY_DELAYED(Topics.DELIVERY_DELAYED, 1),
    ROUTE_RECALCULATED(Topics.ROUTE_RECALCULATED, 1),
    DRIVER_LOCATION_UPDATED(Topics.DRIVER_LOCATION, 1);

    /** Topic names, kept as constants so they can be used in {@code @KafkaListener} annotations. */
    public static final class Topics {
        public static final String ORDER_CREATED = "smartroute.order-created";
        public static final String ORDER_ASSIGNED = "smartroute.order-assigned";
        public static final String DELIVERY_STARTED = "smartroute.delivery-started";
        public static final String DELIVERY_COMPLETED = "smartroute.delivery-completed";
        public static final String DELIVERY_DELAYED = "smartroute.delivery-delayed";
        public static final String ROUTE_RECALCULATED = "smartroute.route-recalculated";
        public static final String DRIVER_LOCATION = "smartroute.driver-location-updated";

        public static final String[] ALL = {ORDER_CREATED, ORDER_ASSIGNED, DELIVERY_STARTED, DELIVERY_COMPLETED,
                DELIVERY_DELAYED, ROUTE_RECALCULATED, DRIVER_LOCATION};

        private Topics() {
        }
    }

    private final String topic;
    private final int version;

    EventType(String topic, int version) {
        this.topic = topic;
        this.version = version;
    }

    public String topic() {
        return topic;
    }

    public int version() {
        return version;
    }
}
