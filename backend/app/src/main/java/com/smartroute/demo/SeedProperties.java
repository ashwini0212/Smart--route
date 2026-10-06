package com.smartroute.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Size and area of the generated demo data. Defaults cover the synthetic 100×100 city around
 * central Bengaluru (origin 12.955, 77.575; ~15 km × 15 km).
 */
@ConfigurationProperties(prefix = "smartroute.seed")
public record SeedProperties(
        long randomSeed,
        int drivers,
        int orders,
        double minLatitude,
        double maxLatitude,
        double minLongitude,
        double maxLongitude) {

    public SeedProperties {
        if (randomSeed == 0) {
            randomSeed = 20261006L;
        }
        if (drivers == 0) {
            drivers = 120;
        }
        if (orders == 0) {
            orders = 600;
        }
        if (minLatitude == 0 && maxLatitude == 0) {
            minLatitude = 12.960;
            maxLatitude = 13.083;
        }
        if (minLongitude == 0 && maxLongitude == 0) {
            minLongitude = 77.580;
            maxLongitude = 77.707;
        }
    }
}
