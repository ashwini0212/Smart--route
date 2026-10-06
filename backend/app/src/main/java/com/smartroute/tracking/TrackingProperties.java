package com.smartroute.tracking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Live tracking settings ({@code smartroute.tracking.*}).
 *
 * @param enabled          false stops the scheduled sweep; it can still be run through the API
 * @param sweepInterval    how often active deliveries are re-checked for lateness and route changes
 * @param lateThreshold    how far past its window a delivery must be predicted to arrive before an alert is
 *                         raised, so a few seconds of noise does not page anyone
 * @param lateGrowth       how much worse an already-reported delay must get before it is reported again
 * @param recalculateShift how much a driver's route duration must change before it counts as recalculated
 * @param heartbeat        how often an idle live stream sends a comment, to keep proxies from closing it
 * @param positionTtl      how long a position is kept in Redis (a driver silent for longer is simply unknown)
 */
@ConfigurationProperties(prefix = "smartroute.tracking")
public record TrackingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("15s") Duration sweepInterval,
        @DefaultValue("60s") Duration lateThreshold,
        @DefaultValue("5m") Duration lateGrowth,
        @DefaultValue("0.1") double recalculateShift,
        @DefaultValue("20s") Duration heartbeat,
        @DefaultValue("1h") Duration positionTtl) {
}
