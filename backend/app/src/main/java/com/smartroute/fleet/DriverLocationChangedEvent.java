package com.smartroute.fleet;

import java.time.Instant;

/** Published (in-process) when a driver's last known position changes. */
public record DriverLocationChangedEvent(long driverId, double latitude, double longitude, Instant at) {
}
