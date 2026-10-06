package com.smartroute.assistant;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the assistant endpoints return. */
public final class AssistantResponses {

    /**
     * One answer.
     *
     * @param facts            bullets the model put under FACTS, parsed out of its answer
     * @param recommendations  bullets under RECOMMENDATIONS: suggestions, not measurements
     * @param uncertainty      bullets under UNCERTAINTY
     * @param text             the answer exactly as the model wrote it, so nothing is lost in parsing
     * @param sectionsParsed   false when the answer did not follow the three-section shape; then only
     *                         {@code text} is meaningful and the client should show it as written
     * @param toolCalls        every tool call made for this answer, in order, with how long each took
     * @param toolRounds       how many times the model asked for tools
     * @param toolLimitReached true when the model was told to answer because it had used its last round
     * @param model            which model answered
     * @param usage            tokens charged for this answer, summed over every request in the loop
     */
    public record Answer(
            List<String> facts,
            List<String> recommendations,
            List<String> uncertainty,
            String text,
            boolean sectionsParsed,
            List<ToolCallRecord> toolCalls,
            int toolRounds,
            boolean toolLimitReached,
            String model,
            Usage usage) {
    }

    /**
     * One tool call, for the audit trail the UI shows under the answer.
     *
     * @param arguments the model's arguments, as JSON
     * @param failed    the tool rejected the call (bad arguments, nothing found) and the model was told so
     */
    public record ToolCallRecord(String tool, String arguments, boolean failed, long millis) {
    }

    @Schema(description = "Tokens charged for this answer, across every request the tool loop made")
    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, int requests) {
    }

    /**
     * Whether the assistant can be used, so the UI can hide it rather than offer a button that fails.
     *
     * @param reason why it is off, in words; empty when it is on
     * @param tools  the tool names it would have, which is also the honest answer to "what can it see?"
     */
    public record Status(boolean enabled, String reason, String model, List<String> tools) {
    }

    private AssistantResponses() {
    }
}
