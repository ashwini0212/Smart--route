package com.smartroute.fleet;

/**
 * Where a driver position came from.
 *
 * <p>It travels with the position into the event payload and the live stream, so a dashboard (or anyone
 * reading the event log) can see that a moving driver is the simulator and not a person with a phone.
 * Phase 10 has no real devices, so everything the simulator produces is labelled {@link #SIMULATION}.
 */
public enum LocationSource {
    /** Reported through the API, by a driver's client. */
    API,
    /** Produced by the movement simulator (synthetic). */
    SIMULATION
}
