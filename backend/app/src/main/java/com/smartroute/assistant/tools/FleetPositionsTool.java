package com.smartroute.assistant.tools;

import com.smartroute.assistant.AssistantTool;
import com.smartroute.assistant.ToolArgs;
import com.smartroute.fleet.LocationSource;
import com.smartroute.tracking.LivePosition;
import com.smartroute.tracking.LivePositions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the fleet is, with the provenance of every position.
 *
 * <p>Each position says whether it came from a driver's client ({@code API}) or from the movement simulator
 * ({@code SIMULATION}), and the result counts both. The system prompt requires the answer to say so: an
 * assistant that reports simulated positions as sightings of real vehicles would be the most expensive kind
 * of wrong this project could ship.
 */
@Component
class FleetPositionsTool implements AssistantTool {

    private static final int MAX_POSITIONS = 100;

    private final LivePositions positions;
    private final Clock clock;
    private final ToolJson json;

    FleetPositionsTool(LivePositions positions, Clock clock, ObjectMapper mapper) {
        this.positions = positions;
        this.clock = clock;
        this.json = new ToolJson(mapper);
    }

    @Override
    public String name() {
        return "fleet_positions";
    }

    @Override
    public String description() {
        return "The last known position of every driver that has reported one, newest first, with how many "
                + "seconds ago it was reported and where it came from: API means a real client reported it, "
                + "SIMULATION means the movement simulator produced it. Counts of both are included. Always "
                + "say in your answer when positions are simulated.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return AssistantTool.schema(Map.of("limit", AssistantTool.field("integer",
                "How many positions to return, 1 to " + MAX_POSITIONS + " (default 25)")));
    }

    @Override
    public String call(ToolArgs args) {
        int limit = args.bounded("limit", 25, 1, MAX_POSITIONS);
        List<LivePosition> all = positions.all();

        Map<LocationSource, Integer> bySource = new EnumMap<>(LocationSource.class);
        for (LivePosition position : all) {
            bySource.merge(position.source(), 1, Integer::sum);
        }

        List<Map<String, Object>> rows = all.stream()
                .sorted(Comparator.comparing(LivePosition::at).reversed())
                .limit(limit)
                .map(this::row)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("driversWithAPosition", all.size());
        result.put("bySource", bySource);
        result.put("returned", rows.size());
        result.put("positions", rows);
        return json.of(result);
    }

    private Map<String, Object> row(LivePosition position) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("driverId", position.driverId());
        row.put("latitude", position.latitude());
        row.put("longitude", position.longitude());
        row.put("at", position.at());
        row.put("ageSeconds", Duration.between(position.at(), clock.instant()).getSeconds());
        row.put("source", position.source());
        return row;
    }
}
