package com.smartroute.assistant;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole endpoint with a configured assistant, one recorded conversation, and no network.
 *
 * <p>The recorded conversation is a real one in shape: the model asks for {@code find_orders}, reads the result,
 * and writes the three sections. What the test checks is everything between the HTTP request and the HTTP
 * response — that the tool actually ran against the database, that the model saw its output, and that the
 * answer arrives split into facts, recommendations and uncertainty with the tool call listed underneath.
 */
@TestPropertySource(properties = {
        "smartroute.assistant.enabled=true",
        "smartroute.assistant.api-key=test-key-not-used-by-the-recorded-model"
})
@Import(AssistantAnswerApiTest.RecordedModel.class)
class AssistantAnswerApiTest extends ApiTestSupport {

    private static final String ANSWER = """
            FACTS
            - 2 orders are in CREATED, which means waiting for a driver.

            RECOMMENDATIONS
            - Dispatch the HIGH priority one first.

            UNCERTAINTY
            - Nothing here says whether a driver is free to take them.
            """;

    @TestConfiguration
    static class RecordedModel {

        @Bean
        @Primary
        AssistantModel recordedAssistantModel() {
            return new ScriptedAssistantModel(
                    ScriptedAssistantModel.callsTool("call-1", "find_orders", Map.of("status", "CREATED")),
                    ScriptedAssistantModel.answers(ANSWER));
        }
    }

    private long warehouse;

    @BeforeEach
    void seedOrders() throws Exception {
        warehouse = createWarehouse("WH-RA");
        createOrder("HIGH");
        createOrder("NORMAL");
    }

    private void createOrder(String priority) throws Exception {
        postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Asha K","dropAddress":"12 Park Road","dropLatitude":12.98,
                 "dropLongitude":77.61,"priority":"%s","weightKg":2.5,"volumeM3":0.01}
                """.formatted(warehouse, priority)).andExpect(status().isCreated());
    }

    @Test
    void statusReportsItAsOn() throws Exception {
        getUrl("/api/assistant/status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.reason").value(""));
    }

    @Test
    void answersWithSectionsTheToolCallAndWhatItCost() throws Exception {
        getUrl("/api/assistant/status").andExpect(jsonPath("$.enabled").value(true));

        postJson("/api/assistant/ask", """
                {"question":"How many orders are waiting for a driver?"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionsParsed").value(true))
                .andExpect(jsonPath("$.facts[0]").value(
                        "2 orders are in CREATED, which means waiting for a driver."))
                .andExpect(jsonPath("$.recommendations[0]").value("Dispatch the HIGH priority one first."))
                .andExpect(jsonPath("$.uncertainty").value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.toolRounds").value(1))
                .andExpect(jsonPath("$.toolLimitReached").value(false))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("find_orders"))
                .andExpect(jsonPath("$.toolCalls[0].failed").value(false))
                .andExpect(jsonPath("$.usage.requests").value(2))
                .andExpect(jsonPath("$.model").value("scripted-model"));
    }

    @Test
    void tooManyQuestionsInAMinuteAreRefusedBeforeTheModelIsCalled() throws Exception {
        // The bucket holds ten per user per minute; the eleventh question is refused without a model call.
        String body = """
                {"question":"How many orders are waiting for a driver?"}
                """;
        for (int i = 0; i < 10; i++) {
            postJson("/api/assistant/ask", body).andExpect(status().isOk());
        }
        postJson("/api/assistant/ask", body)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
