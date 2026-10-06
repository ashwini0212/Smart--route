package com.smartroute.warehouse;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WarehouseApiTest extends ApiTestSupport {

    private static final String VALID = """
            {"code":"WH-1","name":"Central","address":"MG Road","latitude":12.97,"longitude":77.6}
            """;

    @Test
    void createsAndReadsWarehouse() throws Exception {
        long id = body(postJson("/api/warehouses", VALID).andExpect(status().isCreated())).get("id").asLong();
        getUrl("/api/warehouses/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WH-1"))
                .andExpect(jsonPath("$.active").value(true));
        getUrl("/api/warehouses").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateCodeIsConflict() throws Exception {
        postJson("/api/warehouses", VALID).andExpect(status().isCreated());
        postJson("/api/warehouses", VALID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void validationErrorsUseTheStandardErrorFormat() throws Exception {
        postJson("/api/warehouses", """
                {"code":"bad code","name":"","address":"x","latitude":95,"longitude":77.6}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/api/warehouses"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[A-Za-z0-9-]{8,64}")))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("code", "latitude", "name")));
    }

    @Test
    void unknownWarehouseIsNotFound() throws Exception {
        getUrl("/api/warehouses/999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Warehouse 999 was not found"));
    }

    @Test
    void codeIsImmutable() throws Exception {
        long id = body(postJson("/api/warehouses", VALID)).get("id").asLong();
        putJson("/api/warehouses/" + id, VALID.replace("WH-1", "WH-2"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void malformedJsonIsBadRequestWithoutInternals() throws Exception {
        postJson("/api/warehouses", "{\"code\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is malformed or has a value of the wrong type"));
    }

    @Test
    void unknownPathUsesStandardErrorFormat() throws Exception {
        getUrl("/api/does-not-exist")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
