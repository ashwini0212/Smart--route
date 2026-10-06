package com.smartroute.assignment;

import com.smartroute.routing.GeoPoint;

import java.util.Map;

/** Travel time in seconds from each driver to a pickup; unreachable drivers are absent. */
@FunctionalInterface
interface EtaLookup {
    Map<Long, Double> secondsTo(GeoPoint pickup, Map<Long, GeoPoint> drivers);
}
