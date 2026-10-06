package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.sequencing.CostMatrix;
import com.smartroute.algorithms.sequencing.HeldKarp;
import com.smartroute.algorithms.sequencing.NearestNeighbourTour;
import com.smartroute.algorithms.sequencing.Tour;
import com.smartroute.algorithms.sequencing.TwoOpt;

import java.util.ArrayList;
import java.util.List;

/**
 * How much worse is the heuristic than the exact answer? For each size it builds real travel-time matrices
 * on the synthetic city, solves each one both ways, and prints the gap distribution.
 *
 * <p>Not a JMH benchmark: this measures result quality, not speed. Run it with
 * {@code java -cp benchmarks/target/classes:algorithms/target/classes com.smartroute.benchmarks.SequencingGapReport}.
 */
public final class SequencingGapReport {

    private SequencingGapReport() {
    }

    public static void main(String[] args) {
        int instances = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        Graph city = BenchmarkCities.grid(100);
        System.out.println("Heuristic gap against the exact optimum (travel time, synthetic 100x100 city)");
        System.out.println("Each row: " + instances + " random instances, open route (no return leg)");
        System.out.printf("%-6s %-26s %-26s %-18s %s%n", "stops", "nearest neighbour gap %", "+ 2-opt gap %",
                "exact ms", "heuristic ms");
        for (int stops : new int[] {5, 6, 7, 8, 9, 10, 11, 12, 13, 14}) {
            List<Double> greedyGaps = new ArrayList<>();
            List<Double> twoOptGaps = new ArrayList<>();
            double exactMillis = 0;
            double heuristicMillis = 0;
            for (int instance = 0; instance < instances; instance++) {
                CostMatrix matrix = SequencingInstances.travelTimeMatrix(city, stops, 1_000L + instance);
                long t0 = System.nanoTime();
                Tour exact = HeldKarp.solve(matrix, 0, false);
                exactMillis += (System.nanoTime() - t0) / 1e6;
                t0 = System.nanoTime();
                Tour greedy = NearestNeighbourTour.of(matrix, 0, false);
                Tour improved = TwoOpt.improve(matrix, greedy, false);
                heuristicMillis += (System.nanoTime() - t0) / 1e6;
                greedyGaps.add(100 * (greedy.cost() - exact.cost()) / exact.cost());
                twoOptGaps.add(100 * (improved.cost() - exact.cost()) / exact.cost());
            }
            System.out.printf("%-6d %-26s %-26s %-18.2f %.2f%n", stops, summary(greedyGaps), summary(twoOptGaps),
                    exactMillis / instances, heuristicMillis / instances);
        }
    }

    private static String summary(List<Double> gaps) {
        List<Double> sorted = new ArrayList<>(gaps);
        sorted.sort(Double::compareTo);
        double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long optimal = gaps.stream().filter(gap -> gap < 1e-9).count();
        return "mean %.1f, max %.1f, %d/%d optimal".formatted(mean, sorted.getLast(), optimal, gaps.size());
    }
}
