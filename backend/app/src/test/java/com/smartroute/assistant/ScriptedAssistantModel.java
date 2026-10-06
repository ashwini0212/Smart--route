package com.smartroute.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A model that replays recorded replies instead of calling the API.
 *
 * <p>Every assistant test uses this. The point is not only to avoid the network and the bill: a recorded reply
 * is the only way to test the parts that matter here deterministically — what happens when the model asks for a
 * tool that does not exist, calls one with the wrong arguments, or keeps asking for tools until the round cap
 * stops it. A real model would do each of those occasionally, which is not a test.
 *
 * <p>It also records what it was sent, so a test can assert that tool results and the final-round note
 * actually reached the model.
 */
class ScriptedAssistantModel implements AssistantModel {

    /** The script. It is replayed from the start by every {@link #open} call, so one instance can serve
     * several questions — which an integration test needs, because the model is a singleton bean there. */
    private final List<AssistantModel.ModelTurn> script;
    final List<List<AssistantModel.ToolOutput>> receivedOutputs = new ArrayList<>();
    final List<String> receivedNotes = new ArrayList<>();
    String receivedQuestion;
    String receivedSystemPrompt;
    List<AssistantModel.ToolSpec> receivedTools = List.of();

    ScriptedAssistantModel(AssistantModel.ModelTurn... replies) {
        this.script = List.of(replies);
    }

    /** A reply that asks for one tool call. */
    static ModelTurn callsTool(String id, String tool, Map<String, Object> input) {
        return new ModelTurn("", List.of(new ToolCall(id, tool, input)), "tool_use", new TokenUsage(100, 20, 0));
    }

    /** A reply that answers. */
    static ModelTurn answers(String text) {
        return new ModelTurn(text, List.of(), "end_turn", new TokenUsage(200, 50, 0));
    }

    @Override
    public String modelId() {
        return "scripted-model";
    }

    @Override
    public Session open(String systemPrompt, List<ToolSpec> tools) {
        this.receivedSystemPrompt = systemPrompt;
        this.receivedTools = tools;
        List<ModelTurn> remaining = new ArrayList<>(script);
        return new Session() {

            @Override
            public ModelTurn ask(String question) {
                receivedQuestion = question;
                return next(remaining);
            }

            @Override
            public ModelTurn respond(List<ToolOutput> outputs, String note) {
                receivedOutputs.add(outputs);
                receivedNotes.add(note);
                return next(remaining);
            }
        };
    }

    private static ModelTurn next(List<ModelTurn> remaining) {
        if (remaining.isEmpty()) {
            throw new AssertionError("The loop asked for more replies than the script has");
        }
        return remaining.remove(0);
    }
}
