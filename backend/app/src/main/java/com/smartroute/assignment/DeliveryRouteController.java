package com.smartroute.assignment;

import com.smartroute.common.security.Access;
import com.smartroute.common.security.CurrentUser;
import com.smartroute.routing.OptimizeStrategy;
import com.smartroute.routing.OptimizedRoute;
import com.smartroute.routing.RouteMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** The visiting order for a driver's current deliveries. */
@RestController
@RequestMapping("/api/deliveries")
@Tag(name = "Deliveries", description = "A driver's own deliveries and status updates")
class DeliveryRouteController {

    private final DeliveryRouteService service;

    DeliveryRouteController(DeliveryRouteService service) {
        this.service = service;
    }

    @GetMapping("/route")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Visiting order for a driver's active deliveries, from their current position")
    OptimizedRoute forDriver(@RequestParam long driverId,
                             @RequestParam(required = false) RouteMode mode,
                             @RequestParam(required = false) OptimizeStrategy strategy,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                             Instant departAt) {
        return service.forDriver(driverId, mode, strategy, departAt);
    }

    @GetMapping("/mine/route")
    @PreAuthorize(Access.DRIVER)
    @Operation(summary = "Visiting order for the caller's own active deliveries")
    OptimizedRoute mine(@RequestParam(required = false) RouteMode mode,
                        @RequestParam(required = false) OptimizeStrategy strategy) {
        return service.forDriver(CurrentUser.require().driverId(), mode, strategy, null);
    }
}
