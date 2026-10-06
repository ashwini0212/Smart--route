package com.smartroute.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "CREATED, ASSIGNED, true",
            "CREATED, CANCELLED, true",
            "CREATED, DELIVERED, false",
            "ASSIGNED, PICKED_UP, true",
            "ASSIGNED, CREATED, true",
            "ASSIGNED, CANCELLED, true",
            "PICKED_UP, CANCELLED, false",
            "PICKED_UP, IN_TRANSIT, true",
            "IN_TRANSIT, DELIVERED, true",
            "IN_TRANSIT, FAILED, true",
            "IN_TRANSIT, ASSIGNED, false",
            "DELIVERED, IN_TRANSIT, false",
            "CANCELLED, CREATED, false"
    })
    void followsTheLifecycle(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (OrderStatus terminal : new OrderStatus[] {OrderStatus.DELIVERED, OrderStatus.FAILED, OrderStatus.CANCELLED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).isFalse();
            }
        }
    }

    @Test
    void noStatusTransitionsToItself() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }
}
