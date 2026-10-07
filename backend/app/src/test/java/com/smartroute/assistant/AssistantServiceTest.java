package com.smartroute.assistant;

import com.smartroute.assistant.AssistantResponses.Answer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tool loop, driven by recorded replies.
 *
 * <p>These tests are about the loop's behaviour when things go wrong, because that behaviour is what decides
 * whether a dispatcher sees an answer or a 500: a tool called with nonsense arguments, a tool that does not
 * exist, a model that never stops asking. Each of those happens occasionally with a real model and never on
 * demand, so each is recorded here instead.
 */
class AssistantServiceTest {

    private static final AssistantProperties CONFIGURED =
            new AssistantProperties(true, "test-key", "test-model", 1000, 3, "medium");

    private final StubTool warehouses = new StubTool("list_warehouses", "{\"count\":2}");

    @Test
    void answersFromAToolCallAndRecordsIt() {
        ScriptedAssistantModel model = new ScriptedAssistantModel(
                ScriptedAssistantModel.callsTool("call-1", "list_warehouses", Map.of()),
                ScriptedAssistantModel.answers("""
                        FACTS
                        - There are 2 warehouses.

                        RECOMMENDATIONS
                        - Nothing to do.

                        UNCERTAINTY
                        - This says nothing about whether either is busy.
                        """));

        Answer answer = service(model, warehouses).ask("How many warehouses are there?");

        assertThat(answer.facts()).containsExactly("There are 2 warehouses.");
        assertThat(answer.recommendations()).containsExactly("Nothing to do.");
        assertThat(answer.uncertainty()).hasSize(1);
        assertThat(answer.sectionsParsed()).isTrue();
        assertThat(answer.toolRounds()).isEqualTo(1);
        assertThat(answer.toolLimitReached()).isFalse();
        assertThat(answer.model()).isEqualTo("scripted-model");
        assertThat(answer.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("list_warehouses");
            assertThat(call.failed()).isFalse();
        });
        // Both requests are charged, not only the one that produced text.
        assertThat(answer.usage().requests()).isEqualTo(2);
        assertThat(answer.usage().inputTokens()).isEqualTo(300);
        assertThat(answer.usage().outputTokens()).isEqualTo(70);
        assertThat(model.receivedQuestion).isEqualTo("How many warehouses are there?");
        assertThat(model.receivedTools).singleElement()
                .satisfies(spec -> assertThat(spec.name()).isEqualTo("list_warehouses"));
    }

    @Test
    void handsBadArgumentsBackToTheModelAsAFailedResultRatherThanFailingTheRequest() {
        StubTool strict = new StubTool("order_detail", new ToolArgumentException("No order with code ORD-1"));
        ScriptedAssistantModel model = new ScriptedAssistantModel(
                ScriptedAssistantModel.callsTool("call-1", "order_detail", Map.of("order_code", "ORD-1")),
                ScriptedAssistantModel.answers("FACTS\n- No such order.\nRECOMMENDATIONS\n- Check the code.\n"
                        + "UNCERTAINTY\n- The code may have been mistyped."));

        Answer answer = service(model, strict).ask("What is happening with ORD-1?");

        assertThat(answer.toolCalls()).singleElement()
                .satisfies(call -> assertThat(call.failed()).isTrue());
        assertThat(model.receivedOutputs).singleElement().satisfies(outputs ->
                assertThat(outputs).singleElement().satisfies(output -> {
                    assertThat(output.failed()).isTrue();
                    assertThat(output.content()).isEqualTo("No order with code ORD-1");
                }));
    }

    @Test
    void tellsTheModelWhenItAsksForAToolThatDoesNotExist() {
        ScriptedAssistantModel model = new ScriptedAssistantModel(
                ScriptedAssistantModel.callsTool("call-1", "delete_everything", Map.of()),
                ScriptedAssistantModel.answers("FACTS\n- none\nRECOMMENDATIONS\n- none\nUNCERTAINTY\n- none"));

        Answer answer = service(model, warehouses).ask("Delete all the orders");

        assertThat(answer.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("delete_everything");
            assertThat(call.failed()).isTrue();
        });
        assertThat(model.receivedOutputs.getFirst().getFirst().content())
                .contains("No tool named delete_everything")
                .contains("list_warehouses");
    }

    @Test
    void stopsAtTheRoundCapAndSaysSo() {
        // Three rounds are configured; the script asks for a tool four times.
        ScriptedAssistantModel model = new ScriptedAssistantModel(
                ScriptedAssistantModel.callsTool("c1", "list_warehouses", Map.of()),
                ScriptedAssistantModel.callsTool("c2", "list_warehouses", Map.of()),
                ScriptedAssistantModel.callsTool("c3", "list_warehouses", Map.of()),
                ScriptedAssistantModel.callsTool("c4", "list_warehouses", Map.of()));

        Answer answer = service(model, warehouses).ask("Keep going");

        assertThat(answer.toolRounds()).isEqualTo(3);
        assertThat(answer.toolLimitReached()).isTrue();
        assertThat(answer.toolCalls()).hasSize(3);
        // Only the last round carries the note telling it to answer with what it has.
        assertThat(model.receivedNotes).hasSize(3);
        assertThat(model.receivedNotes.get(0)).isNull();
        assertThat(model.receivedNotes.get(1)).isNull();
        assertThat(model.receivedNotes.get(2)).contains("last tool call");
    }

    @Test
    void keepsTheWholeAnswerWhenItDoesNotFollowTheThreeSections() {
        ScriptedAssistantModel model = new ScriptedAssistantModel(
                ScriptedAssistantModel.answers("There are two warehouses and both are active."));

        Answer answer = service(model, warehouses).ask("How many warehouses?");

        assertThat(answer.sectionsParsed()).isFalse();
        assertThat(answer.facts()).isEmpty();
        assertThat(answer.text()).isEqualTo("There are two warehouses and both are active.");
    }

    @Test
    void refusesToAnswerWhenTheFeatureIsNotConfigured() {
        AssistantProperties off = new AssistantProperties(false, null, "test-model", 1000, 3, "medium");
        AssistantService service = new AssistantService(off, java.util.Optional.empty(), List.of(warehouses),
                JsonMapper.builder().build());

        assertThat(service.status().enabled()).isFalse();
        assertThat(service.status().reason()).contains("ASSISTANT_ENABLED");
        assertThat(service.status().tools()).containsExactly("list_warehouses");
        assertThatThrownBy(() -> service.ask("anything"))
                .isInstanceOf(AssistantUnavailableException.class)
                .hasMessageContaining("ASSISTANT_ENABLED");
    }

    private AssistantService service(AssistantModel model, AssistantTool... tools) {
        return new AssistantService(CONFIGURED, java.util.Optional.of(model), List.of(tools),
                JsonMapper.builder().build());
    }

    /** A tool that returns a fixed string, or throws a fixed exception. */
    private static final class StubTool implements AssistantTool {

        private final String name;
        private final String result;
        private final RuntimeException failure;

        StubTool(String name, String result) {
            this(name, result, null);
        }

        StubTool(String name, RuntimeException failure) {
            this(name, null, failure);
        }

        private StubTool(String name, String result, RuntimeException failure) {
            this.name = name;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "A stub";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return AssistantTool.noArguments();
        }

        @Override
        public String call(ToolArgs args) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
