package com.smartroute.support;

import com.smartroute.auth.LoginRateLimiter;
import com.smartroute.common.security.Role;
import com.smartroute.routing.RoadNetworkProvider;
import com.smartroute.routing.RouteOptimizationLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Base class for API integration tests: clean database per test and small JSON helpers.
 * Helpers send an ADMIN bearer token unless a test passes another token (or {@code null} for anonymous).
 */
@IntegrationTest
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TestUsers users;

    @Autowired
    private DatabaseCleaner cleaner;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RoadNetworkProvider roadNetwork;

    @Autowired
    private RouteOptimizationLimiter optimizationLimiter;

    protected String adminToken;

    @BeforeEach
    void cleanDatabase() {
        cleaner.clean();
        loginRateLimiter.reset();
        optimizationLimiter.reset();
        // Shared singletons outlive a test: start every test with an empty cache and free-flow traffic.
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        roadNetwork.replaceTraffic(Map.of());
        adminToken = users.token(Role.ADMIN);
    }

    protected ResultActions postJson(String url, String json) throws Exception {
        return postJson(url, json, adminToken);
    }

    protected ResultActions postJson(String url, String json, String token) throws Exception {
        return mockMvc.perform(auth(post(url).contentType(MediaType.APPLICATION_JSON).content(json), token));
    }

    protected ResultActions putJson(String url, String json) throws Exception {
        return putJson(url, json, adminToken);
    }

    protected ResultActions putJson(String url, String json, String token) throws Exception {
        return mockMvc.perform(auth(put(url).contentType(MediaType.APPLICATION_JSON).content(json), token));
    }

    protected ResultActions getUrl(String url) throws Exception {
        return getUrl(url, adminToken);
    }

    protected ResultActions getUrl(String url, String token) throws Exception {
        return mockMvc.perform(auth(get(url), token));
    }

    protected static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
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
