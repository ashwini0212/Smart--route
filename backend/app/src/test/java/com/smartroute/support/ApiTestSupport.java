package com.smartroute.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Base class for API integration tests: clean database per test and small JSON helpers. */
@IntegrationTest
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleaner cleaner;

    @BeforeEach
    void cleanDatabase() {
        cleaner.clean();
    }

    protected ResultActions postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions putJson(String url, String json) throws Exception {
        return mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions getUrl(String url) throws Exception {
        return mockMvc.perform(get(url));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected long createWarehouse(String code) throws Exception {
        return body(postJson("/api/warehouses", """
                {"code":"%s","name":"Hub %s","address":"1 Test Road","latitude":12.97,"longitude":77.59}
                """.formatted(code, code))).get("id").asLong();
    }

    protected long createVehicle(String plate, String type) throws Exception {
        return body(postJson("/api/vehicles", """
                {"plateNumber":"%s","type":"%s","maxWeightKg":600,"maxVolumeM3":4}
                """.formatted(plate, type))).get("id").asLong();
    }

    protected long createDriver(long warehouseId, Long vehicleId) throws Exception {
        return body(postJson("/api/drivers", """
                {"fullName":"Test Driver","phone":"+91 90000 00001","homeWarehouseId":%d,"vehicleId":%s}
                """.formatted(warehouseId, vehicleId))).get("id").asLong();
    }
}
