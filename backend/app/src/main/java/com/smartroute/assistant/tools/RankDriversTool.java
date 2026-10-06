package com.smartroute.assistant.tools;

import com.smartroute.assignment.CandidateRanking;
import com.smartroute.assignment.CandidateService;
import com.smartroute.assistant.AssistantTool;
import com.smartroute.assistant.ToolArgs;
import com.smartroute.assistant.ToolArgumentException;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the real assignment ranking for one order without assigning anything.
 *
 * <p>This is the tool that answers "why is this order still waiting?". The ranking carries how many drivers
 * were in the radius and how many were dropped for each reason, so the answer can name the actual constraint
 * (no vehicle of the required type, no capacity left, nobody close enough) instead of guessing at one.
 *
 * <p>It is the same code path the dispatcher's "suggest drivers" button uses, which is the point: the
 * assistant is not allowed a private, prettier version of the logic.
 */
@Component
class RankDriversTool implements AssistantTool {

    private static final int MAX_CANDIDATES = 10;

    private final OrderService orders;
    private final CandidateService candidates;
    private final ToolJson json;

    RankDriversTool(OrderService orders, CandidateService candidates, ObjectMapper mapper) {
        this.orders = orders;
        this.candidates = candidates;
        this.json = new ToolJson(mapper);
    }

    @Override
    public String name() {
        return "rank_drivers_for_order";
    }

    @Override
    public String description() {
        return "Ranks drivers for one order by the live assignment score, without assigning anyone. Returns "
                + "the best candidates with their road-network ETA to the pickup, current workload and "
                + "remaining capacity, plus how many drivers were within the search radius and how many were "
                + "excluded for each reason. Use it to explain why an order has no driver, or who the best "
                + "driver would be. The score is a weighted heuristic, not an optimum.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("order_code", AssistantTool.field("string", "The order code, e.g. ORD-000042"));
        properties.put("limit", AssistantTool.field("integer",
                "How many candidates to return, 1 to " + MAX_CANDIDATES + " (default 5)"));
        return AssistantTool.schema(properties, "order_code");
    }

    @Override
    public String call(ToolArgs args) {
        String code = args.requiredText("order_code");
        OrderResponse order = orders.findByCode(code)
                .orElseThrow(() -> new ToolArgumentException("No order with code " + code));
        int limit = args.bounded("limit", 5, 1, MAX_CANDIDATES);

        CandidateRanking ranking = candidates.rank(orders.assignmentView(order.id()), limit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderStatus", order.status());
        result.put("currentDriverId", order.driverId());
        result.put("ranking", ranking);
        result.put("etaNote", "etaSeconds is road-network travel time to the pickup with current traffic.");
        return json.of(result);
    }
}
