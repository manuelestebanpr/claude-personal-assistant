package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.Map;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantMetrics;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantObservations;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Adapts one {@link ToolSpecification} to Spring AI's raw, String-based {@code ToolCallback} SPI.
 */
class ToolCallbackAdapterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();
    private final ToolSpecification specification = new ToolSpecification("get_current_hour", "Returns the time.",
            Map.of("type", "object", "additionalProperties", false));
    private final ToolCallbackAdapter adapter = new ToolCallbackAdapter(specification, toolExecutor, objectMapper,
            observationRegistry, null);

    @Test
    void exposesTheSpecificationAsAToolDefinition() {
        ToolDefinition definition = adapter.getToolDefinition();

        assertThat(definition.name()).isEqualTo("get_current_hour");
        assertThat(definition.description()).isEqualTo("Returns the time.");
        assertThat(objectMapper.readValue(definition.inputSchema(), Map.class))
                .isEqualTo(Map.of("type", "object", "additionalProperties", false));
    }

    @Test
    void parsesTheModelSArgumentsAndReturnsTheExecutorSTextOnSuccess() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of("zone", "Europe/Madrid"))))
                .willReturn(ToolExecutionResult.ok("21:07"));

        String result = adapter.call("{\"zone\":\"Europe/Madrid\"}");

        assertThat(result).isEqualTo("21:07");
    }

    @Test
    void callsTheToolWithNoArgumentsWhenTheModelSendsAnEmptyObject() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of()))).willReturn(ToolExecutionResult.ok("21:07"));

        adapter.call("{}");

        verify(toolExecutor).execute("get_current_hour", Map.of());
    }

    @Test
    void flagsAFailedToolRunSoTheModelCanReadAndSelfCorrect() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of())))
                .willReturn(ToolExecutionResult.failed("unknown timezone"));

        String result = adapter.call("{}");

        assertThat(result).isEqualTo("Error: unknown timezone");
    }

    /**
     * The tool-result span is taken here because this is the only place holding both the parsed
     * arguments and the outcome; Spring AI's own {@code execute_tool} span sees raw strings only.
     */
    @Test
    void observesTheToolResultAsItsOwnBlock() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of()))).willReturn(ToolExecutionResult.ok("21:07"));

        adapter.call("{}");

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AssistantObservations.TOOL_RESULT)
                .that()
                .hasContextualNameEqualTo(AssistantObservations.CONTEXTUAL_NAME_TOOL_RESULT)
                .hasLowCardinalityKeyValue(AssistantObservations.KEY_BLOCK_TYPE,
                        AssistantObservations.BLOCK_TYPE_TOOL_RESULT)
                .hasLowCardinalityKeyValue(AssistantObservations.KEY_OUTCOME, AssistantMetrics.OUTCOME_SUCCESS)
                .hasHighCardinalityKeyValue(AssistantObservations.KEY_TOOL_NAME, "get_current_hour")
                .hasHighCardinalityKeyValue(AssistantObservations.KEY_TOOL_RESULT_CHARS, "5");
    }

    @Test
    void marksTheToolResultObservationFailedWhenTheToolRanAndFailed() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of())))
                .willReturn(ToolExecutionResult.failed("unknown timezone"));

        adapter.call("{}");

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AssistantObservations.TOOL_RESULT)
                .that()
                .hasLowCardinalityKeyValue(AssistantObservations.KEY_OUTCOME, AssistantMetrics.OUTCOME_FAILURE)
                .doesNotHaveError();
    }

    /** A port failure is not the tool answering with {@code error=true}: it propagates, on the span too. */
    @Test
    void stopsTheToolResultObservationWithTheErrorWhenTheExecutorThrows() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of())))
                .willThrow(new IllegalStateException("gateway down"));

        assertThatThrownBy(() -> adapter.call("{}")).isInstanceOf(IllegalStateException.class);

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AssistantObservations.TOOL_RESULT)
                .that()
                .hasLowCardinalityKeyValue(AssistantObservations.KEY_OUTCOME, AssistantMetrics.OUTCOME_FAILURE)
                .hasError();
    }

    /**
     * The regression this guards: Spring AI invokes the callback on a Reactor scheduler thread, so
     * nothing is on the observation thread local by then and the tool-result span would open as a
     * root — the tool call and the MCP round trip landing on a trace of their own. The caller's
     * observation is captured at construction precisely so that stops depending on the thread.
     */
    @Test
    void parentsTheToolResultOnTheCallerSObservationEvenWithNoScopeOnTheThread() {
        given(toolExecutor.execute(eq("get_current_hour"), eq(Map.of()))).willReturn(ToolExecutionResult.ok("21:07"));
        Observation caller = Observation.start("chat.turn.stream", observationRegistry);
        ToolCallbackAdapter parented = new ToolCallbackAdapter(specification, toolExecutor, objectMapper,
                observationRegistry, caller);

        parented.call("{}");
        caller.stop();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AssistantObservations.TOOL_RESULT)
                .that()
                .hasParentObservationEqualTo(caller);
    }
}
