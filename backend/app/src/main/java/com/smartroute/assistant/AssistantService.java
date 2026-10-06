package com.smartroute.assistant;

import com.smartroute.assistant.AssistantModel.ModelTurn;
import com.smartroute.assistant.AssistantModel.Session;
import com.smartroute.assistant.AssistantModel.ToolCall;
import com.smartroute.assistant.AssistantModel.ToolOutput;
import com.smartroute.assistant.AssistantModel.ToolSpec;
import com.smartroute.assistant.AssistantResponses.Answer;
import com.smartroute.assistant.AssistantResponses.Status;
import com.smartroute.assistant.AssistantResponses.ToolCallRecord;
import com.smartroute.assistant.AssistantResponses.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tool-calling loop (FR-24).
 *
 * <p>The loop is small on purpose: ask, run whatever tools the model asked for, send the results back, repeat
 * until it answers or until it has used its rounds. What makes it safe is what it refuses to do — there is no
 * tool here that writes, a tool that throws becomes a message the model can read rather than a failed request,
 * and the number of paid round trips per question is capped by configuration.
 *
 * <p>Tool calls in one round are executed in the order the model asked for them, not concurrently. The tools
 * are database reads on the same connection pool as every other request, and a dispatcher waiting a few
 * hundred milliseconds longer is a better trade than one question occupying several pool connections.
 */
@Service
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final String LAST_ROUND_NOTE = """
            That was your last tool call. Answer now with what you have, and say under UNCERTAINTY that you \
            stopped before you had checked everything you wanted to.""";

    private final AssistantProperties properties;
    private final Optional<AssistantModel> model;
    private final Map<String, AssistantTool> tools;
    private final ObjectMapper mapper;

    AssistantService(AssistantProperties properties, Optional<AssistantModel> model, List<AssistantTool> tools,
                     ObjectMapper mapper) {
        this.properties = properties;
        this.model = model;
        this.mapper = mapper;
        Map<String, AssistantTool> byName = new LinkedHashMap<>();
        tools.stream().sorted(Comparator.comparing(AssistantTool::name))
                .forEach(tool -> byName.put(tool.name(), tool));
        this.tools = Map.copyOf(byName);
    }

    /** What the UI needs to decide whether to offer the assistant at all. */
    public Status status() {
        return new Status(model.isPresent() && properties.configured(), properties.disabledReason(),
                properties.model(), tools.keySet().stream().sorted().toList());
    }

    public Answer ask(String question) {
        AssistantModel llm = model.filter(ignored -> properties.configured())
                .orElseThrow(() -> new AssistantUnavailableException(
                        properties.disabledReason().isEmpty()
                                ? "The assistant is not available."
                                : properties.disabledReason()));

        List<ToolSpec> specs = tools.values().stream()
                .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.inputSchema()))
                .toList();

        Session session = llm.open(AssistantPrompt.SYSTEM, specs);
        List<ToolCallRecord> record = new ArrayList<>();
        AssistantModel.TokenUsage usage = AssistantModel.TokenUsage.NONE;
        int requests = 0;
        int rounds = 0;
        boolean limitReached = false;

        ModelTurn turn = session.ask(question);
        usage = usage.plus(turn.usage());
        requests++;

        while (turn.wantsTools()) {
            rounds++;
            boolean lastRound = rounds >= properties.maxToolRounds();
            List<ToolOutput> outputs = new ArrayList<>();
            for (ToolCall call : turn.calls()) {
                outputs.add(run(call, record));
            }
            turn = session.respond(outputs, lastRound ? LAST_ROUND_NOTE : null);
            usage = usage.plus(turn.usage());
            requests++;
            if (lastRound) {
                limitReached = true;
                break;
            }
        }

        if (turn.wantsTools()) {
            // It asked for more tools after being told to answer. Its text is what we have; the loop stops.
            log.info("Assistant stopped after {} tool rounds with tools still requested", rounds);
        }

        String text = turn.text() == null ? "" : turn.text().strip();
        AnswerSections.Parsed parsed = AnswerSections.parse(text);
        return new Answer(parsed.facts(), parsed.recommendations(), parsed.uncertainty(), text,
                parsed.complete(), List.copyOf(record), rounds, limitReached, llm.modelId(),
                new Usage(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), requests));
    }

    /** Runs one tool call. Anything it throws becomes a failed result the model reads, never a 500. */
    private ToolOutput run(ToolCall call, List<ToolCallRecord> record) {
        AssistantTool tool = tools.get(call.name());
        String arguments = arguments(call);
        long startedAt = System.nanoTime();

        if (tool == null) {
            record.add(new ToolCallRecord(call.name(), arguments, true, 0));
            return new ToolOutput(call.id(), "No tool named " + call.name()
                    + ". Available tools: " + String.join(", ", tools.keySet()), true);
        }

        try {
            String result = tool.call(ToolArgs.of(call.input()));
            record.add(new ToolCallRecord(call.name(), arguments, false, millisSince(startedAt)));
            return new ToolOutput(call.id(), result, false);
        } catch (ToolArgumentException e) {
            record.add(new ToolCallRecord(call.name(), arguments, true, millisSince(startedAt)));
            return new ToolOutput(call.id(), e.getMessage(), true);
        } catch (RuntimeException e) {
            // The model gets the type, not the message: a message can carry internals a user should not see.
            log.warn("Assistant tool {} failed", call.name(), e);
            record.add(new ToolCallRecord(call.name(), arguments, true, millisSince(startedAt)));
            return new ToolOutput(call.id(), "The tool failed (" + e.getClass().getSimpleName()
                    + "). Try a different tool or tell the user this could not be checked.", true);
        }
    }

    private long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String arguments(ToolCall call) {
        try {
            return mapper.writeValueAsString(call.input() == null ? Map.of() : call.input());
        } catch (RuntimeException e) {
            return "{}";
        }
    }
}
