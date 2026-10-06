package com.smartroute.routing;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.LargestComponent;
import com.smartroute.algorithms.graph.generator.CityGraphConfig;
import com.smartroute.algorithms.graph.generator.CityGraphGenerator;
import com.smartroute.algorithms.graph.io.RoadNetworkCsv;
import com.smartroute.algorithms.spatial.NearestNodeIndex;
import com.smartroute.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current {@link RoadNetwork} and swaps it atomically when traffic changes.
 *
 * <p>Readers call {@link #current()} (one volatile read, no lock) and keep that snapshot for the whole
 * request. Writers build a complete new snapshot off to the side (copy-on-write, O(V + E)) and publish it
 * with one reference swap, so no reader ever sees a half-updated graph.
 */
@Component
@EnableConfigurationProperties(RoutingProperties.class)
public class RoadNetworkProvider {

    private static final Logger log = LoggerFactory.getLogger(RoadNetworkProvider.class);
    static final double MAX_TRAFFIC_MULTIPLIER = 10.0;

    private final Graph freeFlow;
    private final NearestNodeIndex index;
    private final String source;
    private final boolean synthetic;
    private final String baseFingerprint;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AtomicReference<RoadNetwork> current = new AtomicReference<>();

    RoadNetworkProvider(RoutingProperties properties, Clock clock, ApplicationEventPublisher events) {
        this.clock = clock;
        this.events = events;
        long started = System.nanoTime();
        Graph raw;
        String dataset = properties.datasetDirectory();
        if (dataset != null && !dataset.isBlank()) {
            raw = RoadNetworkCsv.read(Path.of(dataset));
            source = "road network files in " + dataset;
            synthetic = false;
        } else {
            raw = CityGraphGenerator.generate(CityGraphConfig.grid(
                    properties.syntheticRows(), properties.syntheticColumns(), properties.syntheticSeed()));
            source = "synthetic %dx%d grid (seed %d)".formatted(
                    properties.syntheticRows(), properties.syntheticColumns(), properties.syntheticSeed());
            synthetic = true;
        }
        LargestComponent largest = LargestComponent.of(raw);
        freeFlow = largest.graph();
        index = NearestNodeIndex.of(freeFlow);
        baseFingerprint = sha256(source + "|" + freeFlow.nodeCount() + "|" + freeFlow.edgeCount());
        current.set(RoadNetwork.of(1, baseFingerprint, source, synthetic, freeFlow, index, Map.of(), clock.instant()));
        log.info("Road network loaded from {}: {} nodes, {} edges ({} nodes outside the largest strongly connected component removed) in {} ms",
                source, freeFlow.nodeCount(), freeFlow.edgeCount(), largest.removedNodeCount(),
                (System.nanoTime() - started) / 1_000_000);
    }

    /** The current snapshot. Never null. */
    public RoadNetwork current() {
        return current.get();
    }

    /**
     * Replaces all traffic multipliers (travel time × multiplier on each listed segment; others free-flow).
     *
     * <p>Multipliers must be in [1, 10]: traffic only slows roads down. That also keeps the A* time
     * heuristic (straight line at the fastest free-flow speed) a valid lower bound.
     *
     * <p>{@code synchronized}: two concurrent updates must not both start from the same version.
     */
    public synchronized RoadNetwork replaceTraffic(Map<RoadNetwork.EdgeKey, Double> multipliers) {
        Map<RoadNetwork.EdgeKey, Double> sorted = new TreeMap<>(
                Comparator.comparingInt(RoadNetwork.EdgeKey::from).thenComparingInt(RoadNetwork.EdgeKey::to));
        multipliers.forEach((key, multiplier) -> {
            if (!freeFlow.containsNode(key.from()) || !freeFlow.containsNode(key.to())
                    || freeFlow.outgoing(key.from()).stream().noneMatch(e -> e.to() == key.to())) {
                throw ApiException.businessRule("No road segment from node " + key.from() + " to node " + key.to());
            }
            if (!(multiplier >= 1.0 && multiplier <= MAX_TRAFFIC_MULTIPLIER)) {
                throw ApiException.businessRule("Traffic multiplier must be between 1 and " + MAX_TRAFFIC_MULTIPLIER);
            }
            if (multiplier > 1.0) {
                sorted.put(key, multiplier);
            }
        });
        Graph withTraffic = sorted.isEmpty() ? freeFlow : freeFlow.mapEdges(edge -> {
            Double multiplier = sorted.get(new RoadNetwork.EdgeKey(edge.from(), edge.to()));
            return multiplier == null ? edge
                    : new Edge(edge.from(), edge.to(), edge.distanceMeters(), edge.travelTimeSeconds() * multiplier);
        });
        String fingerprint = sorted.isEmpty() ? baseFingerprint : sha256(baseFingerprint + "|" + sorted);
        RoadNetwork next = RoadNetwork.of(current.get().version() + 1, fingerprint, source, synthetic,
                withTraffic, index, sorted, clock.instant());
        current.set(next);
        log.info("Road network version {}: traffic on {} segments", next.version(), sorted.size());
        // Listeners (the tracking sweep) decide what a changed network means for routes already being driven.
        events.publishEvent(new TrafficChangedEvent(next.version(), sorted.size(), next.builtAt()));
        return next;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
