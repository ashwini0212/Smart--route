package com.smartroute.routing;

import com.smartroute.algorithms.graph.Dijkstra;
import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.ShortestPathTree;
import com.smartroute.algorithms.spatial.NearestNodeIndex;
import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Travel times from many origins (drivers) to one destination (a pickup), with one search.
 *
 * <p>Running Dijkstra from each of n drivers to the warehouse would cost n searches. Instead it runs once
 * from the warehouse on the <em>reversed</em> graph: the cost of reaching driver node d there equals the
 * cost of driving from d to the warehouse on the real one-way streets. The search stops as soon as every
 * driver node is settled, so nearby drivers make it cheap. Times include current traffic.
 *
 * <p>An origin farther than the snap limit from any road has no ETA (reported as unreachable).
 */
@Service
public class EtaService {

    private final RoadNetworkProvider networks;
    private final RoutingProperties properties;
    private final MeterRegistry meters;

    EtaService(RoadNetworkProvider networks, RoutingProperties properties, MeterRegistry meters) {
        this.networks = networks;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * Seconds from each origin to {@code destination}; origins that are off the network or can't reach it
     * are missing from the result. Stops early once every origin is settled.
     */
    public Map<Long, Double> secondsTo(GeoPoint destination, Map<Long, GeoPoint> origins) {
        RoadNetwork network = networks.current();
        int target = snapDestination(network, destination);
        Map<Long, Integer> originNodes = new HashMap<>();
        origins.forEach((id, point) -> snap(network, point).ifPresent(node -> originNodes.put(id, node)));
        if (originNodes.isEmpty()) {
            return Map.of();
        }
        Set<Integer> targets = new HashSet<>(originNodes.values());
        ShortestPathTree tree = timed("targets", () ->
                Dijkstra.shortestPathTree(network.reversed(), target, EdgeWeight.TRAVEL_TIME, targets));
        Map<Long, Double> result = new HashMap<>();
        originNodes.forEach((id, node) -> {
            if (tree.isReachable(node)) {
                result.put(id, tree.costTo(node));
            }
        });
        return result;
    }

    /**
     * A full tree to {@code destination} on the current snapshot, for answering many ETA questions to the
     * same place (the auto-dispatcher asks once per order, but all orders of a warehouse share the pickup).
     */
    public EtaTree treeTo(GeoPoint destination) {
        RoadNetwork network = networks.current();
        int target = snapDestination(network, destination);
        ShortestPathTree tree = timed("full", () ->
                Dijkstra.shortestPathTree(network.reversed(), target, EdgeWeight.TRAVEL_TIME));
        return new EtaTree(network, tree);
    }

    /** ETAs to one destination, computed once on one network snapshot. */
    public final class EtaTree {
        private final RoadNetwork network;
        private final ShortestPathTree tree;

        private EtaTree(RoadNetwork network, ShortestPathTree tree) {
            this.network = network;
            this.tree = tree;
        }

        public OptionalDouble secondsFrom(GeoPoint origin) {
            return snap(network, origin).filter(tree::isReachable)
                    .map(node -> OptionalDouble.of(tree.costTo(node))).orElse(OptionalDouble.empty());
        }

        public long networkVersion() {
            return network.version();
        }
    }

    private int snapDestination(RoadNetwork network, GeoPoint destination) {
        return snap(network, destination).orElseThrow(() -> new ApiException(ErrorCode.LOCATION_OFF_NETWORK,
                "Pickup (%.5f, %.5f) is more than %.0f m from the nearest road".formatted(
                        destination.latitude(), destination.longitude(), properties.maxSnapDistanceMeters())));
    }

    private Optional<Integer> snap(RoadNetwork network, GeoPoint point) {
        return network.index().nearest(point.latitude(), point.longitude())
                .filter(n -> n.distanceMeters() <= properties.maxSnapDistanceMeters())
                .map(NearestNodeIndex.Nearest::nodeId);
    }

    private ShortestPathTree timed(String kind, Supplier<ShortestPathTree> search) {
        return Timer.builder("smartroute.eta.compute").tag("kind", kind).register(meters).record(search);
    }
}
