package com.smartroute.assistant;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.smartroute.fleet.DriverService;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Clock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tools themselves, against real data in a real database.
 *
 * <p>The scripted tests prove the loop handles a tool that fails. These prove the tools return what their
 * descriptions promise, which is the other half: the description is prompt text, so a tool whose output does
 * not match its description is a tool that teaches the model something false.
 *
 * <p>No model is involved. Each test calls the tool exactly as the loop would, with the arguments a model
 * would produce.
 */
class AssistantToolsTest extends ApiTestSupport {

    @Autowired
    private List<AssistantTool> allTools;

    @Autowired
    private DriverService drivers;

    @Autowired
    private Clock clock;

    private Map<String, AssistantTool> tools;
    private long warehouse;

    @BeforeEach
    void setUp() throws Exception {
        tools = allTools.stream().collect(Collectors.toMap(AssistantTool::name, Function.identity()));
        warehouse = createWarehouse("WH-AS");
    }

    private JsonNode call(String tool, Map<String, Object> args) throws Exception {
        return objectMapper.readTree(tools.get(tool).call(ToolArgs.of(args)));
    }

    private String createOrder() throws Exception {
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Asha K","dropAddress":"12 Park Road","dropLatitude":12.98,
                 "dropLongitude":77.61,"priority":"HIGH","weightKg":2.5,"volumeM3":0.01}
                """.formatted(warehouse)).andExpect(status().isCreated())).get("code").asString();
    }

    @Test
    void everyToolHasANameADescriptionAndAnObjectSchema() {
        assertThat(allTools).hasSize(6);
        for (AssistantTool tool : allTools) {
            assertThat(tool.name()).matches("[a-z][a-z_]+");
            // The description is what the model chooses from, so an empty one is a bug, not a style issue.
            assertThat(tool.description()).hasSizeGreaterThan(80);
            assertThat(tool.inputSchema()).containsEntry("type", "object");
        }
    }

    @Test
    void listWarehousesReturnsTheWarehouseAndItsCount() throws Exception {
        JsonNode result = call("list_warehouses", Map.of());

        assertThat(result.get("count").asInt()).isEqualTo(1);
        assertThat(result.get("warehouses").get(0).get("code").asString()).isEqualTo("WH-AS");
    }

    @Test
    void findOrdersFiltersAndReportsHowManyMatchedBeyondWhatItReturned() throws Exception {
        createOrder();
        createOrder();
        createOrder();

        JsonNode all = call("find_orders", Map.of("status", "CREATED", "limit", 2));
        assertThat(all.get("matched").asInt()).isEqualTo(3);
        assertThat(all.get("returned").asInt()).isEqualTo(2);
        assertThat(all.get("orders").get(0).get("status").asString()).isEqualTo("CREATED");

        JsonNode none = call("find_orders", Map.of("status", "DELIVERED"));
        assertThat(none.get("matched").asInt()).isZero();
    }

    @Test
    void findOrdersRejectsAStatusThatDoesNotExistAndListsTheOnesThatDo() {
        assertThatThrownBy(() -> call("find_orders", Map.of("status", "LOST_IN_TRANSIT")))
                .isInstanceOf(ToolArgumentException.class)
                .hasMessageContaining("DELIVERED");
    }

    @Test
    void orderDetailReturnsTheOrderWithItsHistory() throws Exception {
        String code = createOrder();

        JsonNode result = call("order_detail", Map.of("order_code", code));

        assertThat(result.get("order").get("code").asString()).isEqualTo(code);
        assertThat(result.get("order").get("priority").asString()).isEqualTo("HIGH");
        assertThat(result.get("history")).hasSize(1);
        assertThat(result.get("history").get(0).get("toStatus").asString()).isEqualTo("CREATED");
    }

    @Test
    void orderDetailSaysSoWhenTheCodeIsNotAnOrder() {
        assertThatThrownBy(() -> call("order_detail", Map.of("order_code", "ORD-999999")))
                .isInstanceOf(ToolArgumentException.class)
                .hasMessageContaining("ORD-999999");
    }

    @Test
    void rankingAnOrderWithNoDriversExplainsWhyRatherThanReturningAnEmptyList() throws Exception {
        String code = createOrder();

        JsonNode result = call("rank_drivers_for_order", Map.of("order_code", code));

        JsonNode ranking = result.get("ranking");
        assertThat(ranking.get("orderCode").asString()).isEqualTo(code);
        assertThat(ranking.get("driversInRadius").asInt()).isZero();
        assertThat(ranking.get("candidates")).isEmpty();
        // The label travels with the result: the score is a heuristic and says so.
        assertThat(ranking.get("algorithm").asString()).contains("HEURISTIC");
    }

    @Test
    void fleetPositionsCountsBySourceSoSimulatedPositionsCannotBeMistakenForReportedOnes() throws Exception {
        long vehicle = createVehicle("KA01-AS-0001", "VAN");
        long driver = createDriver(warehouse, vehicle);
        // Positions arrive from the event stream or the simulator, never from an endpoint a dispatcher calls,
        // so the service is the way in here too.
        drivers.updateLocation(driver, 12.97, 77.59, clock.instant());

        JsonNode result = call("fleet_positions", Map.of());

        assertThat(result.get("driversWithAPosition").asInt()).isEqualTo(1);
        assertThat(result.get("bySource").get("API").asInt()).isEqualTo(1);
        assertThat(result.get("positions").get(0).get("source").asString()).isEqualTo("API");
        assertThat(result.get("positions").get(0).has("ageSeconds")).isTrue();
    }

    @Test
    void analyticsOverviewCarriesTheDefinitionsWithTheNumbers() throws Exception {
        createOrder();

        JsonNode result = call("analytics_overview", Map.of("days", 7));

        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(result.get("waitingNow").asInt()).isEqualTo(1);
        assertThat(result.get("definitions")).isNotEmpty();
    }

    @Test
    void analyticsOverviewRefusesAWindowOutsideTheAllowedRange() {
        assertThatThrownBy(() -> call("analytics_overview", Map.of("days", 400)))
                .isInstanceOf(ToolArgumentException.class)
                .hasMessageContaining("between 1 and 90");
    }
}
