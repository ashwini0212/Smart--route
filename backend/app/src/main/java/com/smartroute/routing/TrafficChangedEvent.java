package com.smartroute.routing;

import java.time.Instant;

/**
 * Published (in-process) after the road network's traffic has been replaced.
 *
 * <p>Routes already driven on the previous snapshot may no longer be the fastest, which is what the tracking
 * sweep reacts to. The event carries no graph: a listener takes the current snapshot itself.
 *
 * @param slowSegments how many segments are slower than free-flow in the new snapshot
 */
public record TrafficChangedEvent(long networkVersion, int slowSegments, Instant at) {
}
