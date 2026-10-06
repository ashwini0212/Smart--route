package com.smartroute.order;

import com.smartroute.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@Validated
@Tag(name = "Orders", description = "Delivery orders and their lifecycle")
class OrderController {

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an order (status CREATED, code generated)")
    OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order")
    OrderResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "Search orders by status, priority, warehouse and creation time (newest first)")
    PageResponse<OrderResponse> search(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderPriority priority,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        OrderSearch search = new OrderSearch(status, priority, warehouseId, createdFrom, createdTo);
        Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return PageResponse.of(service.search(search, PageRequest.of(page, size, newestFirst)), o -> o);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Status history of an order, oldest first")
    List<OrderStatusChangeResponse> history(@PathVariable long id) {
        return service.history(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order that has not been picked up yet")
    OrderResponse cancel(@PathVariable long id, @Valid @RequestBody CancelOrderRequest request) {
        return service.cancel(id, request.reason());
    }
}
