package com.smartroute.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Road network and route cache settings ({@code smartroute.routing.*}).
 *
 * @param datasetDirectory folder with {@code nodes.csv} and {@code edges.csv} (e.g. an OSM extract made by
 *                         scripts/osm); when empty, the synthetic city is generated instead
 * @param syntheticRows    size of the synthetic grid (150 m blocks)
 * @param syntheticSeed    random seed of the synthetic grid; same seed, same city
 * @param maxSnapDistanceMeters a point farther than this from every road node is rejected as off-network
 * @param cacheTtl         how long a computed route stays in Redis
 */
@ConfigurationProperties(prefix = "smartroute.routing")
public record RoutingProperties(
        String datasetDirectory,
        @DefaultValue("100") int syntheticRows,
        @DefaultValue("100") int syntheticColumns,
        @DefaultValue("42") long syntheticSeed,
        @DefaultValue("300") double maxSnapDistanceMeters,
        @DefaultValue("10m") Duration cacheTtl) {
}
