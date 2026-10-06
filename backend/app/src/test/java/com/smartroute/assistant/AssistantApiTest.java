package com.smartroute.assistant;

import com.smartroute.common.security.Role;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant as the default deployment has it: present, documented, and off.
 *
 * <p>This is the behaviour that matters most for a feature nobody is obliged to configure. The application
 * starts, the endpoint exists, and asking it a question gets a 503 that names the missing setting — not a 500,
 * not a timeout, and not a context that refused to start because a key was absent.
 */
class AssistantApiTest extends ApiTestSupport {

    @Test
    void statusSaysItIsOffAndWhy() throws Exception {
        getUrl("/api/assistant/status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("ASSISTANT_ENABLED")))
                // The tool list is published even while it is off: it is the honest answer to "what could it see?"
                .andExpect(jsonPath("$.tools").value(org.hamcrest.Matchers.hasSize(6)))
                .andExpect(jsonPath("$.tools").value(org.hamcrest.Matchers.hasItem("rank_drivers_for_order")));
    }

    @Test
    void askingWhileItIsOffIsA503WithTheReason() throws Exception {
        postJson("/api/assistant/ask", """
                {"question":"How many orders are waiting?"}
                """)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("ASSISTANT_ENABLED")));
    }

    @Test
    void anEmptyQuestionIsRejectedBeforeAnythingIsSpent() throws Exception {
        postJson("/api/assistant/ask", """
                {"question":"  "}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aQuestionLongerThanTheLimitIsRejected() throws Exception {
        postJson("/api/assistant/ask", """
                {"question":"%s"}
                """.formatted("a".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("question"));
    }

    @Test
    void viewersAndDriversCannotAskAtAll() throws Exception {
        // A viewer can read every number the assistant can read, and still may not ask: this is the one
        // endpoint that costs money per call, so it is staff only.
        postJson("/api/assistant/ask", """
                {"question":"How many orders are waiting?"}
                """, users.token(Role.VIEWER))
                .andExpect(status().isForbidden());

        long warehouse = createWarehouse("WH-AD");
        long driver = createDriver(warehouse, createVehicle("KA01-AD-0001", "VAN"));
        getUrl("/api/assistant/status", users.driverToken(driver))
                .andExpect(status().isForbidden());

        getUrl("/api/assistant/status", null)
                .andExpect(status().isUnauthorized());
    }
}
