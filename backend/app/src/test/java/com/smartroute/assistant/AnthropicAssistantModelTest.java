package com.smartroute.assistant;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.smartroute.assistant.AssistantModel.ModelTurn;
import com.smartroute.assistant.AssistantModel.Session;
import com.smartroute.assistant.AssistantModel.ToolOutput;
import com.smartroute.assistant.AssistantModel.ToolSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SDK adapter, against a local HTTP server that returns recorded Messages API responses.
 *
 * <p>The other tests replace the model entirely, which leaves the one piece of vendor-specific code — turning
 * our tool specs into the API's shape, and its response blocks back into ours — never executed until the first
 * real question. This runs it: a real {@code AnthropicOkHttpClient} against {@code localhost}, no key that
 * works anywhere, no network, no cost.
 *
 * <p>The two recorded bodies are the shapes that matter: one asking for a tool, one answering. What is checked
 * on the way out is that the tool definition and the system prompt reached the wire, and that the second
 * request replays the first assistant turn rather than rebuilding it — the transcript has to stay append-only.
 */
class AnthropicAssistantModelTest {

    private static final String TOOL_USE_REPLY = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"tool_use","id":"toolu_01","name":"find_orders",
                         "input":{"status":"CREATED","limit":5}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":1200,"output_tokens":40,"cache_read_input_tokens":900}}
            """;

    private static final String TEXT_REPLY = """
            {"id":"msg_02","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":"FACTS\\n- 12 orders are waiting."}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":1500,"output_tokens":90}}
            """;

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> replies = new ArrayList<>(List.of(TOOL_USE_REPLY, TEXT_REPLY));

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = replies.remove(0).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsToolCallsAndTextBothWaysAndReplaysTheAssistantTurn() {
        AssistantProperties properties =
                new AssistantProperties(true, "test-key", "claude-opus-5-5", 1000, 6, "medium");
        AnthropicAssistantModel model = new AnthropicAssistantModel(
                AnthropicOkHttpClient.builder()
                        .apiKey("test-key")
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build(),
                properties);

        Session session = model.open("SYSTEM PROMPT UNDER TEST", List.of(new ToolSpec(
                "find_orders", "Finds orders",
                AssistantTool.schema(Map.of("status", AssistantTool.field("string", "One status")), "status"))));

        ModelTurn first = session.ask("What is waiting?");

        assertThat(first.calls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_01");
            assertThat(call.name()).isEqualTo("find_orders");
            assertThat(call.input()).containsEntry("status", "CREATED");
            // The arguments arrive as JSON, so a number stays a number rather than becoming a string.
            assertThat(call.input().get("limit").toString()).isEqualTo("5");
        });
        assertThat(first.text()).isEmpty();
        assertThat(first.stopReason()).contains("tool_use");
        assertThat(first.usage().inputTokens()).isEqualTo(1200);
        assertThat(first.usage().cacheReadTokens()).isEqualTo(900);

        ModelTurn second = session.respond(
                List.of(new ToolOutput("toolu_01", "{\"matched\":12}", false)), "answer now");

        assertThat(second.calls()).isEmpty();
        assertThat(second.text()).isEqualTo("FACTS\n- 12 orders are waiting.");
        assertThat(second.usage().cacheReadTokens()).isZero();

        // Outbound: the tool, the prompt and its cache breakpoint are on the first request.
        assertThat(requestBodies.getFirst())
                .contains("\"name\":\"find_orders\"")
                .contains("SYSTEM PROMPT UNDER TEST")
                .contains("\"cache_control\"")
                .contains("\"effort\":\"medium\"")
                // Thinking is left at the model's default; sending a budget would be rejected.
                .doesNotContain("budget_tokens");

        // Outbound: the second request carries the first answer back unchanged, plus the tool result.
        assertThat(requestBodies.get(1))
                .contains("\"toolu_01\"")
                .contains("\"tool_result\"")
                .contains("{\\\"matched\\\":12}")
                .contains("answer now");
    }
}
