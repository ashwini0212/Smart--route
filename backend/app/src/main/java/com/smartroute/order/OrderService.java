package com.smartroute.order;

import com.smartroute.common.error.ApiException;
import com.smartroute.warehouse.WarehouseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orders;
    private final OrderStatusChangeRepository history;
    private final WarehouseService warehouses;
    private final Clock clock;

    OrderService(OrderRepository orders, OrderStatusChangeRepository history, WarehouseService warehouses, Clock clock) {
        this.orders = orders;
        this.history = history;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        warehouses.requireActive(request.warehouseId());
        if (request.windowEnd() != null && !request.windowEnd().isAfter(clock.instant())) {
            throw ApiException.businessRule("windowEnd is already in the past");
        }
        String code = "ORD-%06d".formatted(orders.nextCodeNumber());
        DeliveryOrder order = orders.save(new DeliveryOrder(code, request.warehouseId(), request.customerName(),
                request.dropAddress(), request.dropLatitude(), request.dropLongitude(), request.priority(),
                request.weightKg(), request.volumeM3(), request.requiredVehicleType(),
                request.windowStart(), request.windowEnd()));
        history.save(new OrderStatusChange(order.getId(), null, OrderStatus.CREATED, "Order created", clock.instant()));
        return OrderResponse.from(order);
    }

    public OrderResponse get(long id) {
        return OrderResponse.from(load(id));
    }

    public Page<OrderResponse> search(OrderSearch search, Pageable pageable) {
        return orders.findAll(OrderSpecifications.matching(search), pageable).map(OrderResponse::from);
    }

    public List<OrderStatusChangeResponse> history(long id) {
        load(id);
        return history.findByOrderIdOrderByChangedAtAscIdAsc(id).stream().map(OrderStatusChangeResponse::from).toList();
    }

    @Transactional
    public OrderResponse cancel(long id, String reason) {
        return transition(id, OrderStatus.CANCELLED, reason);
    }

    /**
     * Single entry point for every status change, so the state machine check and the history row can
     * never be skipped. Used by later modules (assignment, delivery tracking).
     */
    @Transactional
    public OrderResponse transition(long id, OrderStatus target, String reason) {
        DeliveryOrder order = load(id);
        OrderStatus previous = order.transitionTo(target);
        history.save(new OrderStatusChange(order.getId(), previous, target, reason, clock.instant()));
        return OrderResponse.from(order);
    }

    public long count() {
        return orders.count();
    }

    private DeliveryOrder load(long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id));
    }
}
