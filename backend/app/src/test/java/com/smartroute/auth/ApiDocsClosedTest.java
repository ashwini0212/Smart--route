package com.smartroute.auth;

import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same endpoints with {@code API_DOCS_PUBLIC=false}.
 *
 * <p>The OpenAPI document describes every endpoint, parameter and DTO in the system. Open is the right
 * default for a stack bound to localhost, and the wrong one anywhere a stranger can reach the port — so the
 * switch exists, and this test is what says it actually switches something.
 */
@TestPropertySource(properties = "smartroute.security.public-api-docs=false")
class ApiDocsClosedTest extends ApiTestSupport {

    @Test
    void theDocumentIsAdminOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        getUrl("/v3/api-docs", users.token(Role.DISPATCHER)).andExpect(status().isForbidden());
        getUrl("/v3/api-docs").andExpect(status().isOk());
    }

    @Test
    void swaggerUiIsAdminOnlyToo() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
    }
}
