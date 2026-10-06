package com.smartroute.algorithms.graph.io;

import com.smartroute.algorithms.graph.Edge;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Reads and writes the road-network CSV format produced by {@code scripts/osm/osm_roads.py}.
 *
 * <pre>
 * nodes.csv  id,osm_id,lat,lon                              (ids dense: 0..n-1, in order)
 * edges.csv  from_id,to_id,distance_m,travel_time_s,road_class
 * </pre>
 *
 * Plain Java I/O, streaming line by line, so a large extract never has to fit in memory as text.
 * Malformed input fails fast with the file name and line number.
 */
public final class RoadNetworkCsv {

    public static final String NODES_FILE = "nodes.csv";
    public static final String EDGES_FILE = "edges.csv";
    private static final String NODES_HEADER = "id,osm_id,lat,lon";
    private static final String EDGES_HEADER = "from_id,to_id,distance_m,travel_time_s,road_class";

    private RoadNetworkCsv() {
    }

    public static Graph read(Path directory) {
        Graph.Builder builder = Graph.builder();
        readNodes(directory.resolve(NODES_FILE), builder);
        readEdges(directory.resolve(EDGES_FILE), builder);
        return builder.build();
    }

    /** Writes a graph in the same format (osm_id is written as -1 because synthetic graphs have none). */
    public static void write(Graph graph, Path directory) {
        try {
            Files.createDirectories(directory);
            try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(NODES_FILE))) {
                out.write(NODES_HEADER);
                out.newLine();
                for (GraphNode node : graph.nodes()) {
                    out.write(String.format(Locale.ROOT, "%d,-1,%.7f,%.7f", node.id(), node.latitude(), node.longitude()));
                    out.newLine();
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(EDGES_FILE))) {
                out.write(EDGES_HEADER);
                out.newLine();
                for (GraphNode node : graph.nodes()) {
                    for (Edge edge : graph.outgoing(node.id())) {
                        out.write(String.format(Locale.ROOT, "%d,%d,%.3f,%.3f,unknown",
                                edge.from(), edge.to(), edge.distanceMeters(), edge.travelTimeSeconds()));
                        out.newLine();
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write road network to " + directory, e);
        }
    }

    private static void readNodes(Path file, Graph.Builder builder) {
        readLines(file, NODES_HEADER, (fields, lineNumber) -> {
            int id = Integer.parseInt(fields[0]);
            if (id != builder.nodeCount()) {
                throw new IllegalArgumentException("node ids must be 0..n-1 in order, expected "
                        + builder.nodeCount() + " but got " + id);
            }
            builder.addNode(Double.parseDouble(fields[2]), Double.parseDouble(fields[3]));
        }, 4);
    }

    private static void readEdges(Path file, Graph.Builder builder) {
        readLines(file, EDGES_HEADER, (fields, lineNumber) -> builder.addEdge(new Edge(
                Integer.parseInt(fields[0]),
                Integer.parseInt(fields[1]),
                Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]))), 5);
    }

    @FunctionalInterface
    private interface RowHandler {
        void handle(String[] fields, int lineNumber);
    }

    private static void readLines(Path file, String expectedHeader, RowHandler handler, int columns) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null || !header.strip().equals(expectedHeader)) {
                throw new IllegalArgumentException(file + ": expected header '" + expectedHeader + "' but got '" + header + "'");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length != columns) {
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": expected " + columns
                            + " columns but got " + fields.length);
                }
                try {
                    handler.handle(fields, lineNumber);
                } catch (IllegalArgumentException e) { // includes NumberFormatException
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
