package com.smartroute.algorithms.graph.generator;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.algorithms.graph.StronglyConnectedComponents;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CityGraphGeneratorTest {

    @Test
    void sameSeedProducesIdenticalGraph() {
        Graph a = CityGraphGenerator.generate(CityGraphConfig.grid(20, 20, 1));
        Graph b = CityGraphGenerator.generate(CityGraphConfig.grid(20, 20, 1));
        assertThat(a.nodes()).isEqualTo(b.nodes());
        for (int v = 0; v < a.nodeCount(); v++) {
            assertThat(a.outgoing(v)).isEqualTo(b.outgoing(v));
        }
    }

    @Test
    void differentSeedsProduceDifferentGraphs() {
        Graph a = CityGraphGenerator.generate(CityGraphConfig.grid(20, 20, 1));
        Graph b = CityGraphGenerator.generate(CityGraphConfig.grid(20, 20, 2));
        assertThat(a.nodes()).isNotEqualTo(b.nodes());
    }

    @Test
    void createsOneNodePerGridCell() {
        Graph graph = CityGraphGenerator.generate(CityGraphConfig.grid(7, 9, 3));
        assertThat(graph.nodeCount()).isEqualTo(63);
    }

    @Test
    void everyEdgeIsAtLeastTheStraightLineDistance() {
        Graph graph = CityGraphGenerator.generate(CityGraphConfig.grid(30, 30, 5));
        for (GraphNode node : graph.nodes()) {
            for (Edge edge : graph.outgoing(node.id())) {
                double straight = GeoMath.haversineMeters(graph.node(edge.from()), graph.node(edge.to()));
                assertThat(edge.distanceMeters()).isGreaterThanOrEqualTo(straight);
                assertThat(edge.travelTimeSeconds()).isPositive();
            }
        }
    }

    @Test
    void mostOfTheCityIsMutuallyReachable() {
        Graph graph = CityGraphGenerator.generate(CityGraphConfig.grid(40, 40, 9));
        StronglyConnectedComponents scc = StronglyConnectedComponents.of(graph);
        // One-way streets and gaps cut off a few nodes; arterials keep the bulk connected.
        assertThat(scc.sizeOf(scc.largestComponent())).isGreaterThan((int) (graph.nodeCount() * 0.9));
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> CityGraphConfig.grid(0, 5, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
