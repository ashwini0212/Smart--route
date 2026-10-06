package com.smartroute.tracking;

import com.smartroute.fleet.LocationSource;

import java.time.Instant;

/**
 * A driver's last known position as the live layer holds it.
 *
 * @param source where it came from; {@code SIMULATION} means nobody was driving
 */
public record LivePosition(long driverId, double latitude, double longitude, Instant at, LocationSource source) {
}
