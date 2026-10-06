package com.smartroute.order;

import com.smartroute.common.error.ApiException;
import com.smartroute.warehouse.WarehouseService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orders;
    private final OrderStatusChangeRepository history;
    private final WarehouseService warehouses;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /** Targets a driver (or staff on their behalf) may report from the road. */
    private static final Set<OrderStatus> DELIVERY_UPDATES =
            EnumSet.of(OrderStatus.PICKED_UP, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED, OrderStatus.FAILED);
    private static final Set<OrderStatus> ACTIVE =
            EnumSet.of(OrderStatus.ASSIGNED, OrderStatus.PICKED_UP, OrderStatus.IN_TRANSIT);

    OrderService(OrderRepository orders, OrderStatusChangeRepository history, WarehouseService warehouses, Clock clock,
                 ApplicationEventPublisher events) {
        this.orders = orders;
        this.history = history;
        this.warehouses = warehouses;
        this.clock = clock;
        this.events = events;
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
        recordChange(order, null, null, "Order created");
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
     * Status changes that don't involve choosing a driver (cancel, pick-up, delivery, failure). Assigning
     * and unassigning have their own methods because they also set or clear the driver.
     */
    @Transactional
    public OrderResponse transition(long id, OrderStatus target, String reason) {
        if (target == OrderStatus.ASSIGNED || target == OrderStatus.CREATED) {
            throw new IllegalArgumentException("Use assign/unassign to move an order to " + target);
        }
        DeliveryOrder order = load(id);
        Long driver = order.getDriverId();
        OrderStatus previous = order.transitionTo(target);
        recordChange(order, driver, previous, reason);
        return OrderResponse.from(order);
    }

    /**
     * Locks the order row for the rest of the caller's transaction and returns it. The assignment engine
     * calls this first, then reserves the driver's capacity, then {@link #markAssigned}: always order, then
     * driver, so two assignments can't deadlock by locking the same rows in opposite order.
     */
    @Transactional
    public OrderAssignmentView lockForAssignment(long id) {
        return OrderAssignmentView.from(orders.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Order", id)));
    }

    /** CREATED → ASSIGNED. The caller has already reserved the driver's capacity in this transaction. */
    @Transactional
    public OrderResponse markAssigned(long id, long driverId, String reason) {
        DeliveryOrder order = load(id);
        OrderStatus previous = order.assignTo(driverId, clock.instant());
        recordChange(order, driverId, previous, reason);
        return OrderResponse.from(order);
    }

    /** ASSIGNED → CREATED: the order goes back to the dispatch queue; listeners release the driver's capacity. */
    @Transactional
    public OrderResponse unassign(long id, String reason) {
        DeliveryOrder order = load(id);
        Long driver = order.getDriverId();
        OrderStatus previous = order.unassign();
        recordChange(order, driver, previous, reason);
        return OrderResponse.from(order);
    }

    /**
     * A delivery update from the road. With {@code actingDriverId} set (a DRIVER login), only that driver's
     * own orders can be updated; anyone else's order answers 404, so order ids can't be probed.
     */
    @Transactional
    public OrderResponse deliveryUpdate(long id, OrderStatus target, String reason, Long actingDriverId) {
        if (!DELIVERY_UPDATES.contains(target)) {
            throw ApiException.businessRule("A delivery update can only set " + DELIVERY_UPDATES);
        }
        DeliveryOrder order = load(id);
        if (actingDriverId != null && !actingDriverId.equals(order.getDriverId())) {
            throw ApiException.notFound("Order", id);
        }
        return transition(id, target, reason);
    }

    /** Orders waiting for a driver (status CREATED), at most {@code limit}, oldest first. */
    public List<OrderAssignmentView> waitingForDriver(int limit) {
        return orders.findByStatus(OrderStatus.CREATED, PageRequest.of(0, limit, Sort.by("createdAt", "id")))
                .stream().map(OrderAssignmentView::from).toList();
    }

    /** Ids of drivers that currently hold at least one active delivery, lowest first. */
    public List<Long> driversWithActiveDeliveries() {
        return orders.findDriverIdsWithActiveOrders(ACTIVE);
    }

    /** Orders the driver currently has (assigned, picked up or in transit), oldest assignment first. */
    public List<OrderResponse> activeForDriver(long driverId) {
        return orders.findByDriverIdAndStatusInOrderByAssignedAtAscIdAsc(driverId, ACTIVE).stream()
                .map(OrderResponse::from).toList();
    }

    /**
     * Lookup by the code a human uses (ORD-000042). The assistant hears codes, never ids, and an endpoint
     * that made it guess the id would make it guess.
     */
    public Optional<OrderResponse> findByCode(String code) {
        return orders.findByCode(code).map(OrderResponse::from);
    }

    public OrderAssignmentView assignmentView(long id) {
        return OrderAssignmentView.from(load(id));
    }

    private void recordChange(DeliveryOrder order, Long driverId, OrderStatus previous, String reason) {
        Instant now = clock.instant();
        history.save(new OrderStatusChange(order.getId(), previous, order.getStatus(), reason, now));
        events.publishEvent(new OrderStatusChangedEvent(order.getId(), order.getCode(), order.getWarehouseId(),
                order.getPriority(), driverId, previous, order.getStatus(), order.getWeightKg(),
                order.getVolumeM3(), reason, now));
    }

    public long count() {
        return orders.count();
    }

    private DeliveryOrder load(long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id));
    }
}
