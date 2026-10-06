package com.smartroute.benchmarks;

import com.smartroute.algorithms.graph.Graph;
import com.smartroute.algorithms.graph.LargestComponent;
import com.smartroute.algorithms.graph.generator.CityGraphConfig;
import com.smartroute.algorithms.graph.generator.CityGraphGenerator;

/** Benchmark inputs: deterministic synthetic cities restricted to their largest SCC (every pair routable). */
final class BenchmarkCities {

    static final long SEED = 42;

    private BenchmarkCities() {
    }

    static Graph grid(int size) {
        return LargestComponent.of(CityGraphGenerator.generate(CityGraphConfig.grid(size, size, SEED))).graph();
    }
}
