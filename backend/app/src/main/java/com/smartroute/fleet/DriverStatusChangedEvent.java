package com.smartroute.fleet;

/** Published (in-process, inside the changing transaction) when a driver's availability changes. */
public record DriverStatusChangedEvent(long driverId, String driverCode, DriverStatus previous, DriverStatus current) {
}
