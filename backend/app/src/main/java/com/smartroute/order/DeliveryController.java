package com.smartroute.order;

import com.smartroute.common.security.Access;
import com.smartroute.common.security.CurrentUser;
import com.smartroute.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The driver's side of an order: what they carry, and status reports from the road. */
@RestController
@RequestMapping("/api/deliveries")
@Tag(name = "Deliveries", description = "A driver's own deliveries and status updates")
class DeliveryController {

    private final OrderService service;

    DeliveryController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    @PreAuthorize(Access.DRIVER)
    @Operation(summary = "The caller's active deliveries (assigned, picked up, in transit), oldest first")
    List<OrderResponse> mine() {
        return service.activeForDriver(CurrentUser.require().driverId());
    }

    @PutMapping("/{orderId}/status")
    @PreAuthorize(Access.DRIVER + " or " + Access.STAFF)
    @Operation(summary = "Report PICKED_UP, IN_TRANSIT, DELIVERED or FAILED. Drivers can only update their own orders")
    OrderResponse update(@PathVariable long orderId, @Valid @RequestBody DeliveryUpdateRequest request) {
        CurrentUser caller = CurrentUser.require();
        // The driver id comes from the signed token, never from the request.
        Long actingDriver = caller.role() == Role.DRIVER ? caller.driverId() : null;
        String reason = request.reason() == null || request.reason().isBlank()
                ? "Reported by " + caller.role().name().toLowerCase() : request.reason();
        return service.deliveryUpdate(orderId, request.status(), reason, actingDriver);
    }
}
