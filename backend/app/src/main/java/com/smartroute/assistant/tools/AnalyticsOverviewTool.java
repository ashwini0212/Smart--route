package com.smartroute.assistant.tools;

import com.smartroute.analytics.AnalyticsResponses.Overview;
import com.smartroute.analytics.AnalyticsService;
import com.smartroute.assistant.AssistantTool;
import com.smartroute.assistant.ToolArgs;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The Phase 12 analytics overview, definitions included.
 *
 * <p>Every analytics response carries a {@code definitions} list saying how each number was computed. It is
 * sent to the model untouched, and the system prompt tells it to report the definition with the number: a
 * median delivery duration measured from creation to delivery is a different claim from one measured from
 * pickup, and the difference is exactly the kind of thing an assistant would otherwise smooth over.
 */
@Component
class AnalyticsOverviewTool implements AssistantTool {

    private final AnalyticsService analytics;
    private final ToolJson json;

    AnalyticsOverviewTool(AnalyticsService analytics, ObjectMapper mapper) {
        this.analytics = analytics;
        this.json = new ToolJson(mapper);
    }

    @Override
    public String name() {
        return "analytics_overview";
    }

    @Override
    public String description() {
        return "Fleet-wide numbers for the last N days: orders created, delivered, failed and cancelled, "
                + "orders waiting for a driver right now, deliveries under way, on-time rate and delivery "
                + "duration percentiles. The response includes a definitions list saying how each number was "
                + "computed; quote the definition whenever you quote the number.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return AssistantTool.schema(Map.of("days",
                AssistantTool.field("integer", "Window in days, 1 to 90 (default 7)")));
    }

    @Override
    public String call(ToolArgs args) {
        Overview overview = analytics.overview(args.bounded("days", 7, 1, 90));
        return json.of(overview);
    }
}
