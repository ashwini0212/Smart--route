package com.smartroute;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests: the full Spring context starts against PostgreSQL (Flyway migrations applied,
 * Hibernate schema validation passed) and the operational endpoints behave as configured.
 */
class SmartRouteApplicationTests extends ApiTestSupport {

    @Test
    void healthEndpointReportsUp() throws Exception {
        getUrl("/actuator/health")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessAndReadinessProbesAreExposed() throws Exception {
        getUrl("/actuator/health/liveness").andExpect(status().isOk());
        getUrl("/actuator/health/readiness").andExpect(status().isOk());
    }

    @Test
    void healthDoesNotExposeComponentDetails() throws Exception {
        getUrl("/actuator/health").andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void infoEndpointReportsApplicationName() throws Exception {
        getUrl("/actuator/info")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("SmartRoute"));
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        // Anonymous callers are stopped by authentication; even an admin finds nothing there.
        getUrl("/actuator/env", null).andExpect(status().isUnauthorized());
        getUrl("/actuator/env").andExpect(status().isNotFound());
        getUrl("/actuator/beans").andExpect(status().isNotFound());
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        getUrl("/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("SmartRoute API"))
                .andExpect(jsonPath("$.paths['/api/orders']").exists());
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        getUrl("/actuator/health", null).andExpect(status().isOk());
        getUrl("/actuator/info", null).andExpect(status().isOk());
        getUrl("/v3/api-docs", null).andExpect(status().isOk());
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        getUrl("/api/orders")
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/orders")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/orders")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void openApiDocumentDeclaresBearerAuth() throws Exception {
        getUrl("/v3/api-docs", null)
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"));
    }

    @Test
    void everyResponseCarriesACorrelationId() throws Exception {
        getUrl("/actuator/health").andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void safeIncomingCorrelationIdIsReusedAndUnsafeOneReplaced() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health")
                        .header("X-Request-Id", "client-abc-12345"))
                .andExpect(header().string("X-Request-Id", "client-abc-12345"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health")
                        .header("X-Request-Id", "bad\nid injected"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad\nid injected")));
    }
}
