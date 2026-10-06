package com.smartroute.assistant;

import java.util.List;
import java.util.Map;

/**
 * The seam between the assistant loop and whatever language model answers it.
 *
 * <p>Everything specific to a vendor's SDK lives behind this interface, for two reasons. The tests need to
 * drive the loop with recorded replies and no network (see {@code ScriptedAssistantModel}), and the loop
 * itself — how many tool rounds are allowed, what counts as an error, what gets logged — is our logic, not
 * the SDK's, so it is worth owning.
 *
 * <p>A {@link Session} is one question and its tool rounds. The implementation keeps the transcript, because
 * a conversation must be replayed to the model unchanged and append-only: on current models a reasoning block
 * is bound to the conversation that produced it, so rewriting earlier turns invalidates it.
 */
public interface AssistantModel {

    /** Which model answers, for the record in the response. */
    String modelId();

    /** Starts one question. The system prompt and tool list are fixed for its whole life. */
    Session open(String systemPrompt, List<ToolSpec> tools);

    interface Session {

        /** The user's question. Called once, first. */
        ModelTurn ask(String question);

        /**
         * Returns the results of the tools the previous turn asked for.
         *
         * @param note optional instruction added after the results (used to say "that was your last tool
         *             round, answer with what you have")
         */
        ModelTurn respond(List<ToolOutput> outputs, String note);
    }

    /** A tool as the model sees it: a name, a sentence about when to use it, and a JSON Schema. */
    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    }

    /** The model asking for one tool call. {@code input} is the raw JSON object it produced. */
    record ToolCall(String id, String name, Map<String, Object> input) {
    }

    /** What we send back for one call. {@code failed} marks it as an error the model should recover from. */
    record ToolOutput(String callId, String content, boolean failed) {
    }

    /**
     * One reply from the model: its text (empty while it is still calling tools), the calls it wants, and
     * what the exchange cost.
     */
    record ModelTurn(String text, List<ToolCall> calls, String stopReason, TokenUsage usage) {

        boolean wantsTools() {
            return !calls.isEmpty();
        }
    }

    /**
     * What one exchange cost. {@code cacheReadTokens} is the part of the input served from the API's prompt
     * cache: it is reported rather than assumed, because whether a prefix is long enough to be cached at all
     * is a property of the model and the prompt, not something this code can promise.
     */
    record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens) {

        static final TokenUsage NONE = new TokenUsage(0, 0, 0);

        TokenUsage plus(TokenUsage other) {
            return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens);
        }
    }
}
