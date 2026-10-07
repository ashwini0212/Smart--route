package com.smartroute.routing;

import com.smartroute.common.ratelimit.PerUserLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Caps how often one user may ask for a multi-stop optimization.
 *
 * <p>Unlike a route request, this one is expensive by design: the exact algorithm costs O(n²·2ⁿ), so 20
 * stops is roughly 1,600 times the work of 10. A bucket of 20 per minute per user keeps a dashboard
 * usable while making it impossible for one client to occupy the CPU with exact runs.
 */
@Component
public class RouteOptimizationLimiter extends PerUserLimiter {

    RouteOptimizationLimiter(Clock clock) {
        super(20, "Too many optimization requests; try again shortly", clock);
    }
}
