package com.smartroute.routing;

import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.common.security.Access;
import com.smartroute.common.security.CurrentUser;
import com.smartroute.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Routing", description = "Shortest/fastest routes on the road network")
class RouteController {

    private final RouteHistoryService history;
    private final RouteEngine engine;
    private final RoadNetworkProvider networks;
    private final RouteOptimizationService optimizer;
    private final RouteOptimizationLimiter limiter;

    RouteController(RouteHistoryService history, RouteEngine engine, RoadNetworkProvider networks,
                    RouteOptimizationService optimizer, RouteOptimizationLimiter limiter) {
        this.history = history;
        this.engine = engine;
        this.networks = networks;
        this.optimizer = optimizer;
        this.limiter = limiter;
    }

    @PostMapping("/routes/shortest")
    @PreAuthorize(Access.ANY_USER)
    @Operation(summary = "Shortest route by distance (A*, optimal); stored in your route history")
    RouteResponse shortest(@Valid @RequestBody RouteRequest request) {
        return history.computeAndRecord(RouteMode.SHORTEST, request, CurrentUser.require());
    }

    @PostMapping("/routes/fastest")
    @PreAuthorize(Access.ANY_USER)
    @Operation(summary = "Fastest route by travel time with current traffic (A*, optimal); stored in your route history")
    RouteResponse fastest(@Valid @RequestBody RouteRequest request) {
        return history.computeAndRecord(RouteMode.FASTEST, request, CurrentUser.require());
    }

    @PostMapping("/routes/alternatives")
    @PreAuthorize(Access.ANY_USER)
    @Operation(summary = "Up to 3 different routes, best first; alternatives are a heuristic (not stored)")
    List<RouteResponse> alternatives(@Valid @RequestBody AlternativesRequest request) {
        RouteEngine.AlternativesOutcome outcome = engine.alternatives(request.mode(), request.from(), request.to(), request.count());
        return outcome.routes().stream()
                .map(route -> RouteResponse.of(null, route, outcome.from(), outcome.to(), outcome.cached(), null))
                .toList();
    }

    @PostMapping("/routes/optimize")
    @PreAuthorize(Access.ANY_USER)
    @Operation(summary = "Visiting order for up to 20 stops: exact below 11 stops, nearest neighbour + 2-opt above "
            + "[HEURISTIC]. With compare=true, runs the heuristic and reports the exact total beside it")
    OptimizedRoute optimize(@Valid @RequestBody OptimizeRequest request,
                            @RequestParam(defaultValue = "false") boolean compare) {
        limiter.check(CurrentUser.require().id());
        return compare ? optimizer.compareStrategies(request) : optimizer.optimize(request);
    }

    @GetMapping("/routes/{id}")
    @Operation(summary = "A stored route (your own, or any if you are staff or a viewer)")
    RouteResponse get(@PathVariable long id) {
        return history.get(id, CurrentUser.require());
    }

    @GetMapping("/routes")
    @Operation(summary = "Your route history, newest first")
    PageResponse<RouteResponse> mine(@RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(history.mine(CurrentUser.require(), PageRequest.of(page, size)), r -> r);
    }

    @GetMapping("/routing/network")
    @Operation(summary = "The road network in use: source, size, bounds, traffic version")
    NetworkResponse network() {
        RoadNetwork network = networks.current();
        double minLat = Double.MAX_VALUE, minLon = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (GraphNode node : network.graph().nodes()) {
            minLat = Math.min(minLat, node.latitude());
            maxLat = Math.max(maxLat, node.latitude());
            minLon = Math.min(minLon, node.longitude());
            maxLon = Math.max(maxLon, node.longitude());
        }
        return new NetworkResponse(network.version(), network.source(), network.synthetic(),
                network.graph().nodeCount(), network.graph().edgeCount(), network.traffic().size(),
                new NetworkResponse.Bounds(minLat, minLon, maxLat, maxLon), network.builtAt());
    }

    @PutMapping("/routing/traffic")
    @PreAuthorize(Access.ADMIN)
    @Operation(summary = "Replace traffic multipliers (builds a new network version; cached routes stop being used)")
    NetworkResponse replaceTraffic(@Valid @RequestBody TrafficRequest request) {
        Map<RoadNetwork.EdgeKey, Double> multipliers = new HashMap<>();
        request.segments().forEach(s -> multipliers.put(new RoadNetwork.EdgeKey(s.fromNode(), s.toNode()), s.multiplier()));
        networks.replaceTraffic(multipliers);
        return network();
    }
}
