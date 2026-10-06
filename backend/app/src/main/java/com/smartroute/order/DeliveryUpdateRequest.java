package com.smartroute.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A status report from the road: PICKED_UP, IN_TRANSIT, DELIVERED or FAILED, with an optional note. */
public record DeliveryUpdateRequest(@NotNull OrderStatus status, @Size(max = 255) String reason) {
}
