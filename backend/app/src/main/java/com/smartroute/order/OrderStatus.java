package com.smartroute.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle as a finite state machine. Every allowed move is listed here, so an illegal
 * transition (e.g. DELIVERED → IN_TRANSIT) is rejected in one place instead of being checked ad hoc.
 *
 * <pre>
 * CREATED ──► ASSIGNED ──► PICKED_UP ──► IN_TRANSIT ──► DELIVERED
 *    │          │  ▲            │             │
 *    │          │  └ (driver    └──► FAILED ◄─┘
 *    ▼          ▼    offline: back to CREATED)
 * CANCELLED ◄───┘
 * </pre>
 */
public enum OrderStatus {
    CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT, DELIVERED, FAILED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(ASSIGNED, CANCELLED),
            ASSIGNED, EnumSet.of(PICKED_UP, CREATED, CANCELLED),
            PICKED_UP, EnumSet.of(IN_TRANSIT, FAILED),
            IN_TRANSIT, EnumSet.of(DELIVERED, FAILED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            FAILED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Statuses in which the order is on a driver's vehicle or heading to it. */
    public boolean isActive() {
        return this == ASSIGNED || this == PICKED_UP || this == IN_TRANSIT;
    }
}
