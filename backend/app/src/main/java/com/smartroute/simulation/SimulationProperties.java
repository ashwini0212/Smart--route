package com.smartroute.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the simulators ({@code smartroute.simulation.*}). Everything here is off by default: it
 * invents data, and invented data must be asked for.
 *
 * @param drivers        move drivers that have active deliveries along their route
 * @param traffic        slow random road segments down and clear them again
 * @param tick           how often the driver simulator moves everyone
 * @param speedKph       how fast a simulated driver drives (a flat number, not a model)
 * @param trafficTick    how often the traffic simulator changes the network
 * @param trafficSegments how many segments it slows down each time
 * @param maxMultiplier  the worst slowdown it applies (the network itself allows up to 10)
 * @param seed           fixed seed, so a demo can be reproduced
 */
@ConfigurationProperties(prefix = "smartroute.simulation")
public record SimulationProperties(
        @DefaultValue("false") boolean drivers,
        @DefaultValue("false") boolean traffic,
        @DefaultValue("2s") Duration tick,
        @DefaultValue("30") double speedKph,
        @DefaultValue("30s") Duration trafficTick,
        @DefaultValue("40") int trafficSegments,
        @DefaultValue("4.0") double maxMultiplier,
        @DefaultValue("42") long seed) {

    /** Metres a simulated driver covers in one tick. */
    public double metersPerTick() {
        return speedKph * 1000 / 3600 * (tick.toMillis() / 1000.0);
    }
}
