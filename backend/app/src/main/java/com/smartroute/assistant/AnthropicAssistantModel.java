package com.smartroute.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Anthropic Messages API behind {@link AssistantModel}.
 *
 * <p>This is the only class in the project that knows about a model provider. Two details in it are not
 * obvious:
 *
 * <p><b>The transcript is append-only.</b> Each assistant reply is added back to the next request as the
 * {@code Message} object the API returned, not as something rebuilt from its text. On current models a
 * reasoning block belongs to the conversation that produced it, so rewriting or dropping earlier turns
 * invalidates it; replaying the response object verbatim is both simpler and the supported shape.
 *
 * <p><b>Thinking is left at its default.</b> On the model this is written against, thinking is always on and
 * cannot be disabled; how hard it thinks is set with effort instead ({@code smartroute.assistant.effort}).
 * There is no thinking budget to configure, and passing one would be rejected.
 */
class AnthropicAssistantModel implements AssistantModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAssistantModel.class);

    private final AnthropicClient client;
    private final AssistantProperties properties;

    AnthropicAssistantModel(AnthropicClient client, AssistantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String modelId() {
        return properties.model();
    }

    @Override
    public Session open(String systemPrompt, List<ToolSpec> tools) {
        return new ApiSession(systemPrompt, tools.stream().map(AnthropicAssistantModel::toSdkTool).toList());
    }

    /** One question and its tool rounds. Not thread-safe, and not meant to outlive one request. */
    private final class ApiSession implements Session {

        private final String systemPrompt;
        private final List<Tool> tools;
        /** User messages ({@link MessageParam}) and assistant replies ({@link Message}), in order. */
        private final List<Object> transcript = new ArrayList<>();

        private ApiSession(String systemPrompt, List<Tool> tools) {
            this.systemPrompt = systemPrompt;
            this.tools = tools;
        }

        @Override
        public ModelTurn ask(String question) {
            transcript.add(MessageParam.builder().role(MessageParam.Role.USER).content(question).build());
            return exchange();
        }

        @Override
        public ModelTurn respond(List<ToolOutput> outputs, String note) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (ToolOutput output : outputs) {
                blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(output.callId())
                        .content(output.content())
                        .isError(output.failed())
                        .build()));
            }
            if (note != null && !note.isBlank()) {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(note).build()));
            }
            transcript.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks)
                    .build());
            return exchange();
        }

        private ModelTurn exchange() {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(properties.model())
                    .maxTokens(properties.maxTokens())
                    .outputConfig(OutputConfig.builder().effort(effort()).build())
                    // One cache breakpoint at the end of the fixed prefix (tools, then this prompt). Whether
                    // the prefix is long enough to be cached is the API's call; usage reports what happened.
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                            .text(systemPrompt)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()));
            tools.forEach(params::addTool);
            for (Object entry : transcript) {
                if (entry instanceof Message message) {
                    params.addMessage(message);
                } else {
                    params.addMessage((MessageParam) entry);
                }
            }

            Message response;
            try {
                response = client.messages().create(params.build());
            } catch (AnthropicException e) {
                log.warn("Assistant request to the model API failed", e);
                throw new AssistantFailedException("The assistant could not reach the model API.", e);
            }
            transcript.add(response);
            return read(response);
        }
    }

    private OutputConfig.Effort effort() {
        String configured = properties.effort() == null ? "medium" : properties.effort();
        return OutputConfig.Effort.of(configured.toLowerCase(Locale.ROOT));
    }

    /** Turns one API response into the shape the loop works with. */
    private static ModelTurn read(Message response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(part -> {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part.text());
            });
            block.toolUse().ifPresent(use -> calls.add(new ToolCall(use.id(), use.name(), inputOf(use._input()))));
        }
        return new ModelTurn(text.toString(), List.copyOf(calls),
                response.stopReason().map(Object::toString).orElse(""),
                new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens(),
                        response.usage().cacheReadInputTokens().orElse(0L)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputOf(JsonValue input) {
        Object converted = input.convert(Map.class);
        return converted instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** Our schema map is already JSON Schema; it is handed over as-is rather than rebuilt field by field. */
    @SuppressWarnings("unchecked")
    private static Tool toSdkTool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Object properties = schema.get("properties");
        Object required = schema.get("required");

        Tool.InputSchema.Properties.Builder propertyBuilder = Tool.InputSchema.Properties.builder();
        if (properties instanceof Map<?, ?> map) {
            map.forEach((key, value) -> propertyBuilder.putAdditionalProperty(String.valueOf(key),
                    JsonValue.from(value)));
        }

        Tool.InputSchema.Builder inputSchema = Tool.InputSchema.builder().properties(propertyBuilder.build());
        if (required instanceof List<?> list && !list.isEmpty()) {
            inputSchema.required(((List<String>) list));
        }

        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(inputSchema.build())
                .build();
    }
}
