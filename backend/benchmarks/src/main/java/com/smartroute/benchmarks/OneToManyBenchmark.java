package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.Dijkstra;
import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.ShortestPathTree;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * ETA from N drivers to one pickup: N separate Dijkstra runs vs one run on the reversed graph
 * that stops once all N driver nodes are settled. This is the design choice behind the assignment
 * engine (Phase 7).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OneToManyBenchmark {

    @Param({"100"})
    int gridSize;

    @Param({"10", "100"})
    int drivers;

    private Graph graph;
    private Graph reversed;
    private int pickup;
    private List<Integer> driverNodes;

    @Setup(Level.Trial)
    public void setUp() {
        graph = BenchmarkCities.grid(gridSize);
        reversed = graph.reversed();
        Random random = new Random(BenchmarkCities.SEED);
        pickup = random.nextInt(graph.nodeCount());
        driverNodes = new ArrayList<>();
        for (int i = 0; i < drivers; i++) {
            driverNodes.add(random.nextInt(graph.nodeCount()));
        }
    }

    @Benchmark
    public double separateRunsPerDriver() {
        double sum = 0;
        for (int driver : driverNodes) {
            sum += Dijkstra.shortestPath(graph, driver, pickup, EdgeWeight.TRAVEL_TIME).cost();
        }
        return sum;
    }

    @Benchmark
    public double singleReversedRun() {
        ShortestPathTree tree = Dijkstra.shortestPathTree(reversed, pickup, EdgeWeight.TRAVEL_TIME, driverNodes);
        double sum = 0;
        for (int driver : driverNodes) {
            sum += tree.costTo(driver);
        }
        return sum;
    }
}
