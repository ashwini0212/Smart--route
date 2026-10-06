package com.smartroute.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Replaces all traffic: listed segments get their multiplier, every other segment is free-flow. */
public record TrafficRequest(@NotNull @Size(max = 50_000) List<@Valid @NotNull Segment> segments) {

    /** @param multiplier travel time factor, 1 (free-flow) to 10 */
    public record Segment(int fromNode, int toNode, double multiplier) {
    }
}
