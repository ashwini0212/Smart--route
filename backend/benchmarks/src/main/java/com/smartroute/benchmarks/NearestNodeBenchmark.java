package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.algorithms.spatial.NearestNodeIndex;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Snapping a GPS point to the nearest graph node: k-d tree vs scanning every node. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class NearestNodeBenchmark {

    private static final int QUERIES = 1024;

    @Param({"100", "200"})
    int gridSize;

    private Graph graph;
    private NearestNodeIndex index;
    private double[] latitudes;
    private double[] longitudes;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        graph = BenchmarkCities.grid(gridSize);
        index = NearestNodeIndex.of(graph);
        double minLat = graph.nodes().stream().mapToDouble(GraphNode::latitude).min().orElseThrow();
        double maxLat = graph.nodes().stream().mapToDouble(GraphNode::latitude).max().orElseThrow();
        double minLon = graph.nodes().stream().mapToDouble(GraphNode::longitude).min().orElseThrow();
        double maxLon = graph.nodes().stream().mapToDouble(GraphNode::longitude).max().orElseThrow();
        Random random = new Random(BenchmarkCities.SEED);
        latitudes = new double[QUERIES];
        longitudes = new double[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            latitudes[i] = minLat + random.nextDouble() * (maxLat - minLat);
            longitudes[i] = minLon + random.nextDouble() * (maxLon - minLon);
        }
    }

    @Benchmark
    public int kdTree() {
        int i = next;
        next = (next + 1) % QUERIES;
        return index.nearest(latitudes[i], longitudes[i]).orElseThrow().nodeId();
    }

    @Benchmark
    public int bruteForce() {
        int i = next;
        next = (next + 1) % QUERIES;
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (GraphNode node : graph.nodes()) {
            double d = GeoMath.haversineMeters(latitudes[i], longitudes[i], node.latitude(), node.longitude());
            if (d < bestDistance) {
                bestDistance = d;
                best = node.id();
            }
        }
        return best;
    }
}
