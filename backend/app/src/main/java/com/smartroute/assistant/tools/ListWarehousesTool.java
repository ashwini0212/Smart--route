package com.smartroute.assistant.tools;

import com.smartroute.assistant.AssistantTool;
import com.smartroute.assistant.ToolArgs;
import com.smartroute.warehouse.WarehouseResponse;
import com.smartroute.warehouse.WarehouseService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Answers "where do we dispatch from?", and turns a warehouse name into the id the other tools need. */
@Component
class ListWarehousesTool implements AssistantTool {

    private final WarehouseService warehouses;
    private final ToolJson json;

    ListWarehousesTool(WarehouseService warehouses, ObjectMapper mapper) {
        this.warehouses = warehouses;
        this.json = new ToolJson(mapper);
    }

    @Override
    public String name() {
        return "list_warehouses";
    }

    @Override
    public String description() {
        return "Lists every warehouse with its id, code, name, address, coordinates and whether it is active. "
                + "Use it to turn a warehouse name the user typed into the id the other tools need.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return AssistantTool.noArguments();
    }

    @Override
    public String call(ToolArgs args) {
        List<WarehouseResponse> all = warehouses.list();
        return json.of(Map.of("count", all.size(), "warehouses", all));
    }
}
