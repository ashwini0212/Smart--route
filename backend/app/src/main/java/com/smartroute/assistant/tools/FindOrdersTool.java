package com.smartroute.assistant.tools;

import com.smartroute.assistant.AssistantTool;
import com.smartroute.assistant.ToolArgs;
import com.smartroute.order.OrderPriority;
import com.smartroute.order.OrderResponse;
import com.smartroute.order.OrderSearch;
import com.smartroute.order.OrderService;
import com.smartroute.order.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds orders by the filters a dispatcher actually asks about: status, warehouse, driver, priority.
 *
 * <p>It returns a summary per order rather than the whole record. The full record is one more call away
 * ({@code order_detail}) and most questions are answered by the summary, so sending twenty full orders would
 * be paying input tokens for fields nobody asked about.
 */
@Component
class FindOrdersTool implements AssistantTool {

    private static final int MAX_RESULTS = 25;

    private final OrderService orders;
    private final ToolJson json;

    FindOrdersTool(OrderService orders, ObjectMapper mapper) {
        this.orders = orders;
        this.json = new ToolJson(mapper);
    }

    @Override
    public String name() {
        return "find_orders";
    }

    @Override
    public String description() {
        return "Finds orders, newest first, with optional filters: status (CREATED, ASSIGNED, PICKED_UP, "
                + "IN_TRANSIT, DELIVERED, FAILED, CANCELLED), priority (LOW, NORMAL, HIGH, URGENT), "
                + "warehouse_id, driver_id. Returns a summary per order plus the total number that matched, "
                + "so you can report how many there are even when you only list some. Use status=CREATED to "
                + "find orders waiting for a driver.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", AssistantTool.field("string", "One order status; omit for any status"));
        properties.put("priority", AssistantTool.field("string", "One priority; omit for any priority"));
        properties.put("warehouse_id", AssistantTool.field("integer", "Only orders from this warehouse"));
        properties.put("driver_id", AssistantTool.field("integer", "Only orders held by this driver"));
        properties.put("limit", AssistantTool.field("integer",
                "How many orders to return, 1 to " + MAX_RESULTS + " (default 10)"));
        return AssistantTool.schema(properties);
    }

    @Override
    public String call(ToolArgs args) {
        OrderSearch search = new OrderSearch(
                args.enumeration("status", OrderStatus.class).orElse(null),
                args.enumeration("priority", OrderPriority.class).orElse(null),
                args.number("warehouse_id").orElse(null),
                args.number("driver_id").orElse(null),
                null, null);
        int limit = args.bounded("limit", 10, 1, MAX_RESULTS);

        Page<OrderResponse> page = orders.search(search,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> summaries = page.getContent().stream().map(this::summary).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", page.getTotalElements());
        result.put("returned", summaries.size());
        result.put("orders", summaries);
        return json.of(result);
    }

    private Map<String, Object> summary(OrderResponse order) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", order.code());
        row.put("status", order.status());
        row.put("priority", order.priority());
        row.put("warehouseId", order.warehouseId());
        row.put("driverId", order.driverId());
        row.put("dropAddress", order.dropAddress());
        row.put("windowEnd", order.windowEnd());
        row.put("createdAt", order.createdAt());
        return row;
    }
}
