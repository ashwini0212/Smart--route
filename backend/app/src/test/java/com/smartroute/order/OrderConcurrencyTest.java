package com.smartroute.order;

import com.smartroute.support.DatabaseCleaner;
import com.smartroute.support.IntegrationTest;
import com.smartroute.warehouse.WarehouseRequest;
import com.smartroute.warehouse.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two requests read the same order, both change it. Without optimistic locking the second write would
 * silently overwrite the first ("lost update"). With @Version the second write fails.
 */
@IntegrationTest
class OrderConcurrencyTest {

    @Autowired
    private OrderService service;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private WarehouseService warehouses;

    @Autowired
    private DatabaseCleaner cleaner;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void staleUpdateIsRejected() {
        long warehouseId = warehouses.create(new WarehouseRequest("WH-C", "C", "addr", 12.9, 77.6)).id();
        long id = service.create(new CreateOrderRequest(warehouseId, "C K", "addr", 12.95, 77.6,
                OrderPriority.NORMAL, BigDecimal.ONE, new BigDecimal("0.010"), null, null, null)).id();

        DeliveryOrder firstCopy = repository.findById(id).orElseThrow();   // both "requests" read version 0
        DeliveryOrder secondCopy = repository.findById(id).orElseThrow();

        firstCopy.transitionTo(OrderStatus.ASSIGNED);
        repository.saveAndFlush(firstCopy);                                 // version becomes 1

        secondCopy.transitionTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> repository.saveAndFlush(secondCopy))     // still thinks version is 0
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ASSIGNED);
    }
}
