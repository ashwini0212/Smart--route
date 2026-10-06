package com.smartroute.order;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderApiTest extends ApiTestSupport {

    private long warehouse;

    @BeforeEach
    void setUp() throws Exception {
        warehouse = createWarehouse("WH-A");
    }

    private String order(String priority) {
        return """
                {"warehouseId":%d,"customerName":"Asha K","dropAddress":"12 Park Road","dropLatitude":12.98,
                 "dropLongitude":77.61,"priority":"%s","weightKg":2.5,"volumeM3":0.01}
                """.formatted(warehouse, priority);
    }

    private long createOrder(String priority) throws Exception {
        return body(postJson("/api/orders", order(priority)).andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void createdOrderStartsInCreatedWithHistory() throws Exception {
        long id = createOrder("URGENT");
        getUrl("/api/orders/" + id)
                .andExpect(jsonPath("$.code").value(matchesPattern("ORD-\\d{6}")))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.weightKg").value(2.5));
        getUrl("/api/orders/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].toStatus").value("CREATED"));
    }

    @Test
    void cancelRecordsHistoryAndSecondCancelIsRejected() throws Exception {
        long id = createOrder("NORMAL");
        postJson("/api/orders/" + id + "/cancel", "{\"reason\":\"Customer request\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        getUrl("/api/orders/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].fromStatus").value("CREATED"))
                .andExpect(jsonPath("$[1].reason").value("Customer request"));
        postJson("/api/orders/" + id + "/cancel", "{\"reason\":\"again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void searchFiltersByStatusAndPriority() throws Exception {
        createOrder("URGENT");
        createOrder("LOW");
        long cancelled = createOrder("URGENT");
        postJson("/api/orders/" + cancelled + "/cancel", "{\"reason\":\"test\"}");

        getUrl("/api/orders?status=CREATED&priority=URGENT")
                .andExpect(jsonPath("$.totalElements").value(1));
        getUrl("/api/orders?status=CANCELLED").andExpect(jsonPath("$.totalElements").value(1));
        getUrl("/api/orders?warehouseId=" + warehouse).andExpect(jsonPath("$.totalElements").value(3));
        getUrl("/api/orders?warehouseId=999").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void timeWindowMustBeOrdered() throws Exception {
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
        String body = order("HIGH").replace("\"volumeM3\":0.01}",
                "\"volumeM3\":0.01,\"windowStart\":\"%s\",\"windowEnd\":\"%s\"}".formatted(start, start.minusSeconds(60)));
        postJson("/api/orders", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timeWindowValid"));
    }

    @Test
    void windowInThePastIsRejected() throws Exception {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String body = order("HIGH").replace("\"volumeM3\":0.01}",
                "\"volumeM3\":0.01,\"windowEnd\":\"%s\"}".formatted(past));
        postJson("/api/orders", body).andExpect(status().isUnprocessableContent());
    }

    @Test
    void inactiveWarehouseCannotShip() throws Exception {
        putJson("/api/warehouses/" + warehouse + "/active?active=false", "").andExpect(status().isOk());
        postJson("/api/orders", order("LOW"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Warehouse WH-A is inactive"));
    }

    @Test
    void rejectsNonPositiveWeightAndMissingFields() throws Exception {
        postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"","dropAddress":"x","dropLatitude":12.9,"dropLongitude":77.6,
                 "priority":"LOW","weightKg":0,"volumeM3":0.01}
                """.formatted(warehouse))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    void unknownPriorityIsMalformed() throws Exception {
        postJson("/api/orders", order("SUPER_URGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
