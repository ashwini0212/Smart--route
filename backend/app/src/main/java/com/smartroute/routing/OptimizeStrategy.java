package com.smartroute.routing;

/** Which sequencing algorithm to use. */
public enum OptimizeStrategy {
    /** Exact while the input is small enough, heuristic above that. */
    AUTO,
    /** Held-Karp: proven shortest order; refuses inputs that are too large. */
    EXACT,
    /** Nearest neighbour + 2-opt: no guarantee, but no size limit either. */
    HEURISTIC
}
