package com.smartroute.order;

import java.time.Instant;

public record OrderStatusChangeResponse(OrderStatus fromStatus, OrderStatus toStatus, String reason, Instant changedAt) {

    static OrderStatusChangeResponse from(OrderStatusChange c) {
        return new OrderStatusChangeResponse(c.getFromStatus(), c.getToStatus(), c.getReason(), c.getChangedAt());
    }
}
