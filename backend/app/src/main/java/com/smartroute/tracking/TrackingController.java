package com.smartroute.tracking;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.security.Access;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tracking")
@Tag(name = "Tracking", description = "Where the drivers are, and the live feed behind the map")
class TrackingController {

    private final LivePositions positions;
    private final LiveStream stream;
    private final DeliveryWatch watch;

    TrackingController(LivePositions positions, LiveStream stream, DeliveryWatch watch) {
        this.positions = positions;
        this.stream = stream;
        this.watch = watch;
    }

    @GetMapping("/drivers")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Last known position of every driver, newest first")
    List<LivePosition> drivers() {
        return positions.all();
    }

    @GetMapping("/drivers/{id}")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Last known position of one driver")
    LivePosition driver(@PathVariable long id) {
        return positions.of(id).orElseThrow(() -> ApiException.notFound("Driver position", id));
    }

    /**
     * The live feed. The browser opens it once and keeps it open; frames are named
     * {@code driver-moved}, {@code order-status}, {@code delivery-delayed}, {@code route-recalculated} and
     * {@code heartbeat}. A client that falls behind is dropped and should reconnect, then read
     * {@code /api/tracking/drivers} for the current state.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Server-sent event stream of driver movement, status changes, delays and recalculations")
    SseEmitter stream() {
        return stream.open();
    }

    @GetMapping("/stream/clients")
    @PreAuthorize(Access.STAFF)
    @Operation(summary = "How many dashboards are currently connected to the live stream")
    Map<String, Integer> clients() {
        return Map.of("connected", stream.connectedClients());
    }

    @PostMapping("/sweep")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Run the delay and route check now instead of waiting for the next scheduled sweep")
    DeliveryWatch.SweepResult sweep() {
        return watch.sweep("requested by an administrator");
    }

    @PostMapping("/positions/rebuild")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Rebuild the live position cache from the database")
    Map<String, Integer> rebuild() {
        return Map.of("positions", positions.rebuild());
    }
}
