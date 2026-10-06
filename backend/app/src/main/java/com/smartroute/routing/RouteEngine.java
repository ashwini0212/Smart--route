package com.smartroute.routing;

import com.smartroute.algorithms.graph.AStar;
import com.smartroute.algorithms.graph.AlternativeRoutes;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.algorithms.graph.Path;
import com.smartroute.algorithms.spatial.NearestNodeIndex;
import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Point-to-point routing on the in-memory road network.
 *
 * <ol>
 *   <li>Take one network snapshot for the whole request.</li>
 *   <li>Snap both points to their nearest road node (k-d tree, O(log V)); reject points farther than
 *       the configured limit.</li>
 *   <li>Look up the cache by (network fingerprint, mode, node pair).</li>
 *   <li>On a miss run A* (optimal; distance or time heuristic) and store the result.</li>
 * </ol>
 */
@Service
public class RouteEngine {

    private final RoadNetworkProvider networks;
    private final RouteCache cache;
    private final RoutingProperties properties;
    private final MeterRegistry meters;

    RouteEngine(RoadNetworkProvider networks, RouteCache cache, RoutingProperties properties, MeterRegistry meters) {
        this.networks = networks;
        this.cache = cache;
        this.properties = properties;
        this.meters = meters;
    }

    public RouteOutcome route(RouteMode mode, GeoPoint from, GeoPoint to) {
        RoadNetwork network = networks.current();
        SnappedPoint a = snap(network, from, "Start");
        SnappedPoint b = snap(network, to, "Destination");
        String key = RouteCache.key(network, mode, a.nodeId(), b.nodeId(), "");
        Optional<List<RoutePath>> cached = cache.get(key);
        if (cached.isPresent() && !cached.get().isEmpty()) {
            return new RouteOutcome(cached.get().getFirst(), a, b, true);
        }
        Path path = timed(mode, "astar",
                () -> AStar.shortestPath(network.graph(), a.nodeId(), b.nodeId(), mode.weight(), mode.heuristic(network)));
        if (!path.isFound()) {
            throw noRoute();
        }
        RoutePath result = toRoutePath(network, mode, path, "A*", true);
        cache.put(key, List.of(result));
        return new RouteOutcome(result, a, b, false);
    }

    /** Up to {@code count} different routes, best first ({@link AlternativeRoutes}, a heuristic). */
    public AlternativesOutcome alternatives(RouteMode mode, GeoPoint from, GeoPoint to, int count) {
        RoadNetwork network = networks.current();
        SnappedPoint a = snap(network, from, "Start");
        SnappedPoint b = snap(network, to, "Destination");
        String key = RouteCache.key(network, mode, a.nodeId(), b.nodeId(), ":alt" + count);
        Optional<List<RoutePath>> cached = cache.get(key);
        if (cached.isPresent()) {
            return new AlternativesOutcome(cached.get(), a, b, true);
        }
        List<Path> paths = timed(mode, "alternatives", () -> AlternativeRoutes.find(network.graph(),
                a.nodeId(), b.nodeId(), mode.weight(), mode.heuristic(network), count));
        if (paths.isEmpty()) {
            throw noRoute();
        }
        List<RoutePath> result = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            result.add(i == 0 ? toRoutePath(network, mode, paths.get(i), "A*", true)
                    : toRoutePath(network, mode, paths.get(i), "penalty alternative [HEURISTIC]", false));
        }
        cache.put(key, result);
        return new AlternativesOutcome(result, a, b, false);
    }

    /** Snapping as the route endpoints use it; shared with multi-stop optimization. */
    public SnappedPoint snapOrFail(RoadNetwork network, GeoPoint point, String label) {
        return snap(network, point, label);
    }

    private SnappedPoint snap(RoadNetwork network, GeoPoint point, String label) {
        NearestNodeIndex.Nearest nearest = network.index().nearest(point.latitude(), point.longitude())
                .orElseThrow(() -> new ApiException(ErrorCode.LOCATION_OFF_NETWORK, "The road network is empty"));
        if (nearest.distanceMeters() > properties.maxSnapDistanceMeters()) {
            throw new ApiException(ErrorCode.LOCATION_OFF_NETWORK, "%s (%.5f, %.5f) is %.0f m from the nearest road; the limit is %.0f m"
                    .formatted(label, point.latitude(), point.longitude(), nearest.distanceMeters(),
                            properties.maxSnapDistanceMeters()));
        }
        GraphNode node = network.graph().node(nearest.nodeId());
        return new SnappedPoint(node.id(), node.latitude(), node.longitude(), nearest.distanceMeters());
    }

    private static RoutePath toRoutePath(RoadNetwork network, RouteMode mode, Path path, String algorithm, boolean optimal) {
        Graph graph = network.graph();
        List<double[]> coordinates = new ArrayList<>(path.nodeIds().size());
        for (int id : path.nodeIds()) {
            GraphNode node = graph.node(id);
            coordinates.add(new double[] {node.latitude(), node.longitude()});
        }
        return new RoutePath(mode, path.nodeIds().getFirst(), path.nodeIds().getLast(), path.totalDistanceMeters(),
                path.totalTravelTimeSeconds(), coordinates, algorithm, optimal, path.nodesSettled(), network.version());
    }

    private <T> T timed(RouteMode mode, String algorithm, Supplier<T> search) {
        return Timer.builder("smartroute.route.compute").tag("mode", mode.name()).tag("algorithm", algorithm)
                .publishPercentileHistogram()
                .register(meters)
                .record(search);
    }

    private static ApiException noRoute() {
        return new ApiException(ErrorCode.NO_ROUTE, "No road connects these points in the direction of travel");
    }

    /** Where an input coordinate was attached to the network. */
    public record SnappedPoint(int nodeId, double latitude, double longitude, double snapDistanceMeters) {
    }

    public record RouteOutcome(RoutePath route, SnappedPoint from, SnappedPoint to, boolean cached) {
    }

    public record AlternativesOutcome(List<RoutePath> routes, SnappedPoint from, SnappedPoint to, boolean cached) {
    }
}
