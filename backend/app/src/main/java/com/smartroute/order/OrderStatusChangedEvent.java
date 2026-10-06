package com.smartroute.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published inside the transaction of every order status change. Listeners run in the same transaction,
 * so a side effect (releasing the driver's capacity) commits or rolls back together with the change.
 *
 * @param driverId the driver responsible before the change (null if none), so an unassign still names them
 * @param warehouseId where the order ships from; carried here so a listener (or an event consumer) does not
 *                    have to load the order again to know that
 */
public record OrderStatusChangedEvent(long orderId, String code, long warehouseId, OrderPriority priority,
                                      Long driverId, OrderStatus previous, OrderStatus current,
                                      BigDecimal weightKg, BigDecimal volumeM3, String reason, Instant at) {

    /** True when the order stopped occupying space on a driver's vehicle. */
    public boolean leftDriver() {
        return driverId != null && previous != null && previous.isActive() && !current.isActive();
    }
}
