package com.smartroute.algorithms.spatial;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.algorithms.graph.generator.CityGraphConfig;
import com.smartroute.algorithms.graph.generator.CityGraphGenerator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NearestNodeIndexTest {

    @Test
    void emptyGraphHasNoNearestNode() {
        assertThat(NearestNodeIndex.of(Graph.builder().build()).nearest(12.9, 77.5)).isEmpty();
    }

    @Test
    void singleNodeIsAlwaysNearest() {
        Graph.Builder builder = Graph.builder();
        builder.addNode(12.9, 77.5);
        NearestNodeIndex index = NearestNodeIndex.of(builder.build());
        assertThat(index.nearest(13.5, 78.0).orElseThrow().nodeId()).isZero();
    }

    @Test
    void exactNodePositionReturnsThatNodeWithZeroDistance() {
        Graph city = CityGraphGenerator.generate(CityGraphConfig.grid(30, 30, 4));
        NearestNodeIndex index = NearestNodeIndex.of(city);
        GraphNode node = city.node(417);
        NearestNodeIndex.Nearest nearest = index.nearest(node.latitude(), node.longitude()).orElseThrow();
        assertThat(nearest.nodeId()).isEqualTo(417);
        assertThat(nearest.distanceMeters()).isZero();
    }

    @Test
    void duplicatePointsResolveToLowestId() {
        Graph.Builder builder = Graph.builder();
        builder.addNode(12.9, 77.5);
        builder.addNode(12.95, 77.55);
        builder.addNode(12.95, 77.55);
        NearestNodeIndex index = NearestNodeIndex.of(builder.build());
        assertThat(index.nearest(12.95, 77.55).orElseThrow().nodeId()).isEqualTo(1);
    }

    @Test
    void agreesWithBruteForceOnRandomQueries() {
        Graph city = CityGraphGenerator.generate(CityGraphConfig.grid(80, 80, 21));
        NearestNodeIndex index = NearestNodeIndex.of(city);
        Random random = new Random(3);
        for (int i = 0; i < 2_000; i++) {
            // Queries inside and slightly outside the city's bounding box.
            double lat = 12.950 + random.nextDouble() * 0.12;
            double lon = 77.570 + random.nextDouble() * 0.12;
            double bruteForceBest = Double.POSITIVE_INFINITY;
            for (GraphNode node : city.nodes()) {
                bruteForceBest = Math.min(bruteForceBest, GeoMath.haversineMeters(lat, lon, node.latitude(), node.longitude()));
            }
            // The tree uses a flat projection; over a city it matches great-circle distance to centimetres.
            assertThat(index.nearest(lat, lon).orElseThrow().distanceMeters()).isCloseTo(bruteForceBest, within(0.5));
        }
    }
}
