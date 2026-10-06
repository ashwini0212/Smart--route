package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.sequencing.CostMatrix;
import com.smartroute.algorithms.sequencing.HeldKarp;
import com.smartroute.algorithms.sequencing.NearestNeighbourTour;
import com.smartroute.algorithms.sequencing.Tour;
import com.smartroute.algorithms.sequencing.TwoOpt;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Visiting order for a delivery run: exact dynamic programming (Held-Karp, O(n²·2ⁿ)) against nearest
 * neighbour plus 2-opt (O(n²) per pass). The matrices are real road travel times on the synthetic city,
 * so the shapes are the ones the API deals with.
 *
 * <p>Run time is only half the comparison; {@link SequencingGapReport} measures how far from optimal the
 * heuristic lands on the same instances.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StopSequencingBenchmark {

    @Param({"8", "10", "12", "14"})
    private int stops;

    private Graph city;
    private CostMatrix matrix;

    @Setup
    public void setUp() {
        city = BenchmarkCities.grid(100);
        matrix = SequencingInstances.travelTimeMatrix(city, stops, BenchmarkCities.SEED);
    }

    @Benchmark
    public double heldKarpExact() {
        return HeldKarp.solve(matrix, 0, false).cost();
    }

    @Benchmark
    public double nearestNeighbourThenTwoOpt() {
        Tour start = NearestNeighbourTour.of(matrix, 0, false);
        return TwoOpt.improve(matrix, start, false).cost();
    }

    @Benchmark
    public double nearestNeighbourOnly() {
        return NearestNeighbourTour.of(matrix, 0, false).cost();
    }

    /**
     * Building the matrix: one early-stopping Dijkstra per point on the 10k-node city. Included because it
     * is the part the sequencing has to beat to matter — if the matrix costs far more, the choice of
     * sequencing algorithm is noise in the total.
     */
    @Benchmark
    public double costMatrix() {
        return SequencingInstances.travelTimeMatrix(city, stops, BenchmarkCities.SEED).cost(0, 1);
    }
}
