package com.smartroute.simulation;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.routing.RoadNetwork;
import com.smartroute.routing.RoadNetworkProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * <b>[SIMULATION]</b> Slows a random set of road segments down, and clears them again on the next tick.
 *
 * <p>This is not a traffic model. It is a random walk over the network whose only purpose is to make the
 * system's reaction to changing traffic observable: cache keys change, routes are recomputed, deliveries that
 * can no longer make their window raise an alert. Real traffic is correlated in space and time (a jam spreads
 * along a corridor and builds over minutes) and nothing here reproduces that, which is why the numbers it
 * produces are never used as measurements.
 *
 * <p>Off unless {@code smartroute.simulation.traffic=true}, and it logs a warning at startup when on. The
 * multipliers it writes go through the same validated path as an administrator's
 * {@code PUT /api/routing/traffic}, so it cannot put the network in a state the API could not.
 */
@Component
@ConditionalOnProperty(name = "smartroute.simulation.traffic", havingValue = "true")
@EnableConfigurationProperties(SimulationProperties.class)
public class TrafficSimulator {

    private static final Logger log = LoggerFactory.getLogger(TrafficSimulator.class);
    /** The slowest a simulated jam can be; below it, a segment is left at free-flow. */
    static final double MIN_MULTIPLIER = 1.2;

    private final RoadNetworkProvider networks;
    private final SimulationProperties properties;
    private final Random random;

    TrafficSimulator(RoadNetworkProvider networks, SimulationProperties properties) {
        this.networks = networks;
        this.properties = properties;
        this.random = new Random(properties.seed());
        log.warn("[SIMULATION] traffic simulator is ON: {} random segments slowed up to {}x every {}",
                properties.trafficSegments(), properties.maxMultiplier(), properties.trafficTick());
    }

    @Scheduled(fixedDelayString = "${smartroute.simulation.traffic-tick:30s}")
    public void changeTraffic() {
        RoadNetwork network = networks.current();
        Map<RoadNetwork.EdgeKey, Double> multipliers = randomJams(network.graph(), properties.trafficSegments());
        networks.replaceTraffic(multipliers);
        log.debug("[SIMULATION] traffic replaced on {} segments", multipliers.size());
    }

    /** Picks {@code count} segments at random (with replacement) and gives each a slowdown. */
    Map<RoadNetwork.EdgeKey, Double> randomJams(Graph graph, int count) {
        List<Edge> edges = new ArrayList<>();
        for (int node = 0; node < graph.nodeCount() && edges.size() < count * 20; node++) {
            if (graph.containsNode(node)) {
                edges.addAll(graph.outgoing(node));
            }
        }
        Map<RoadNetwork.EdgeKey, Double> multipliers = new HashMap<>();
        if (edges.isEmpty()) {
            return multipliers;
        }
        double span = properties.maxMultiplier() - MIN_MULTIPLIER;
        for (int i = 0; i < count; i++) {
            Edge edge = edges.get(random.nextInt(edges.size()));
            multipliers.put(new RoadNetwork.EdgeKey(edge.from(), edge.to()),
                    MIN_MULTIPLIER + random.nextDouble() * span);
        }
        return multipliers;
    }
}
