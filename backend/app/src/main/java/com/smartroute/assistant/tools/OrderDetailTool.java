package com.smartroute.assistant.tools;

import com.smartroute.assistant.AssistantTool;
import com.smartroute.assistant.ToolArgs;
import com.smartroute.assistant.ToolArgumentException;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderService;
import com.smartroute.order.OrderStatusChangeResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One order and everything that has happened to it.
 *
 * <p>The status history is the part worth sending: "why is this order late" is answered by the gaps between
 * its transitions, not by its current status.
 */
@Component
class OrderDetailTool implements AssistantTool {

    private final OrderService orders;
    private final ToolJson json;

    OrderDetailTool(OrderService orders, ObjectMapper mapper) {
        this.orders = orders;
        this.json = new ToolJson(mapper);
    }

    @Override
    public String name() {
        return "order_detail";
    }

    @Override
    public String description() {
        return "One order by its code (for example ORD-000042): the full record and every status change with "
                + "its timestamp and reason. Use it to answer questions about a specific order, including how "
                + "long it spent in each status.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return AssistantTool.schema(
                Map.of("order_code", AssistantTool.field("string", "The order code, e.g. ORD-000042")),
                "order_code");
    }

    @Override
    public String call(ToolArgs args) {
        String code = args.requiredText("order_code");
        OrderResponse order = orders.findByCode(code)
                .orElseThrow(() -> new ToolArgumentException("No order with code " + code));
        List<OrderStatusChangeResponse> history = orders.history(order.id());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("history", history);
        result.put("historyNote", "Changes are in the order they were recorded; changedAt is UTC.");
        return json.of(result);
    }
}
