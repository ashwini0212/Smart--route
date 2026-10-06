package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.AStar;
import com.smartroute.algorithms.graph.BreadthFirstSearch;
import com.smartroute.algorithms.graph.Dijkstra;
import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.Heuristic;
import com.smartroute.algorithms.graph.Heuristics;
import com.smartroute.algorithms.graph.Path;
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

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Dijkstra vs A* on synthetic cities of increasing size.
 *
 * <p>Two trip types, because A*'s benefit depends on how far the target is:
 * <ul>
 *   <li>LOCAL: target within 15 road segments of the source (a typical delivery hop in one zone).</li>
 *   <li>CROSS_CITY: uniformly random source and target anywhere in the city.</li>
 * </ul>
 * Each benchmark invocation answers one query, cycling through 512 pre-generated pairs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ShortestPathBenchmark {

    private static final int QUERIES = 512;
    private static final int LOCAL_TRIP_MAX_HOPS = 15;

    @Param({"50", "100", "200"})
    int gridSize;

    @Param({"DISTANCE", "TRAVEL_TIME"})
    String weightName;

    @Param({"LOCAL", "CROSS_CITY"})
    String tripType;

    private Graph graph;
    private EdgeWeight weight;
    private Heuristic heuristic;
    private int[] sources;
    private int[] targets;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        graph = BenchmarkCities.grid(gridSize);
        boolean distance = weightName.equals("DISTANCE");
        weight = distance ? EdgeWeight.DISTANCE : EdgeWeight.TRAVEL_TIME;
        heuristic = distance ? Heuristics.straightLineDistance(graph) : Heuristics.straightLineTravelTime(graph);

        Random random = new Random(BenchmarkCities.SEED);
        sources = new int[QUERIES];
        targets = new int[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            int s = random.nextInt(graph.nodeCount());
            sources[i] = s;
            if (tripType.equals("LOCAL")) {
                List<Integer> nearby = BreadthFirstSearch.withinHops(graph, s, LOCAL_TRIP_MAX_HOPS);
                targets[i] = nearby.get(random.nextInt(nearby.size()));
            } else {
                targets[i] = random.nextInt(graph.nodeCount());
            }
        }
    }

    private int nextQuery() {
        int i = next;
        next = (next + 1) % QUERIES;
        return i;
    }

    @Benchmark
    public Path dijkstra() {
        int i = nextQuery();
        return Dijkstra.shortestPath(graph, sources[i], targets[i], weight);
    }

    @Benchmark
    public Path aStar() {
        int i = nextQuery();
        return AStar.shortestPath(graph, sources[i], targets[i], weight, heuristic);
    }
}
