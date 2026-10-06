package com.smartroute.algorithms.graph.io;

import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.generator.CityGraphConfig;
import com.smartroute.algorithms.graph.generator.CityGraphGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoadNetworkCsvTest {

    @TempDir
    Path dir;

    @Test
    void readsTheFormatWrittenByTheOsmScript() throws IOException {
        Files.writeString(dir.resolve("nodes.csv"), """
                id,osm_id,lat,lon
                0,1001,12.97,77.59
                1,1002,12.971,77.59
                """);
        Files.writeString(dir.resolve("edges.csv"), """
                from_id,to_id,distance_m,travel_time_s,road_class
                0,1,111.2,20.0,residential
                1,0,111.2,20.0,residential
                """);
        Graph graph = RoadNetworkCsv.read(dir);
        assertThat(graph.nodeCount()).isEqualTo(2);
        assertThat(graph.edgeCount()).isEqualTo(2);
        assertThat(graph.node(1).latitude()).isEqualTo(12.971);
        assertThat(graph.outgoing(0).getFirst().travelTimeSeconds()).isEqualTo(20.0);
    }

    @Test
    void roundTripPreservesGraph() {
        Graph original = CityGraphGenerator.generate(CityGraphConfig.grid(10, 10, 3));
        RoadNetworkCsv.write(original, dir);
        Graph copy = RoadNetworkCsv.read(dir);
        assertThat(copy.nodeCount()).isEqualTo(original.nodeCount());
        assertThat(copy.edgeCount()).isEqualTo(original.edgeCount());
        assertThat(copy.node(42).latitude()).isCloseTo(original.node(42).latitude(), within(1e-7));
        assertThat(copy.outgoing(7).getFirst().distanceMeters())
                .isCloseTo(original.outgoing(7).getFirst().distanceMeters(), within(1e-3));
    }

    @Test
    void rejectsWrongHeader() throws IOException {
        Files.writeString(dir.resolve("nodes.csv"), "node,lat,lon\n");
        Files.writeString(dir.resolve("edges.csv"), "from_id,to_id,distance_m,travel_time_s,road_class\n");
        assertThatThrownBy(() -> RoadNetworkCsv.read(dir)).hasMessageContaining("expected header");
    }

    @Test
    void reportsLineNumberOfMalformedRow() throws IOException {
        Files.writeString(dir.resolve("nodes.csv"), "id,osm_id,lat,lon\n0,1,12.9,77.5\n1,2,abc,77.5\n");
        Files.writeString(dir.resolve("edges.csv"), "from_id,to_id,distance_m,travel_time_s,road_class\n");
        assertThatThrownBy(() -> RoadNetworkCsv.read(dir)).hasMessageContaining("nodes.csv:3");
    }

    @Test
    void rejectsNonDenseNodeIds() throws IOException {
        Files.writeString(dir.resolve("nodes.csv"), "id,osm_id,lat,lon\n0,1,12.9,77.5\n5,2,12.9,77.5\n");
        Files.writeString(dir.resolve("edges.csv"), "from_id,to_id,distance_m,travel_time_s,road_class\n");
        assertThatThrownBy(() -> RoadNetworkCsv.read(dir)).hasMessageContaining("0..n-1");
    }

    @Test
    void rejectsEdgeToMissingNodeAndNegativeDistance() throws IOException {
        Files.writeString(dir.resolve("nodes.csv"), "id,osm_id,lat,lon\n0,1,12.9,77.5\n");
        Files.writeString(dir.resolve("edges.csv"), "from_id,to_id,distance_m,travel_time_s,road_class\n0,3,1,1,x\n");
        assertThatThrownBy(() -> RoadNetworkCsv.read(dir)).hasMessageContaining("edges.csv:2");
        Files.writeString(dir.resolve("edges.csv"), "from_id,to_id,distance_m,travel_time_s,road_class\n0,0,-1,1,x\n");
        assertThatThrownBy(() -> RoadNetworkCsv.read(dir)).hasMessageContaining("non-negative");
    }
}
