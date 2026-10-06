package com.smartroute.order;

import java.time.Instant;

/** Optional filters for listing orders. Any null field means "don't filter on this". */
public record OrderSearch(OrderStatus status, OrderPriority priority, Long warehouseId, Long driverId,
                          Instant createdFrom, Instant createdTo) {
}
