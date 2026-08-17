package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.Map;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantMetrics;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantObservations;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;

/**
 * Adapts one {@link ToolSpecification} to Spring AI's raw, String-based {@link ToolCallback} SPI,
 * translating its JSON arguments/result into and out of the module's own {@link ToolExecutor}
 * port.
 *
 * <p>This is also where the tool-result span is taken. Spring AI already wraps the call in an
 * {@code execute_tool <name>} span, but that span sees only the raw strings going past; this
 * method is the one point that holds the parsed arguments <em>and</em> the
 * {@link ToolExecutionResult} with its error flag, which is what separates "the tool ran and
 * answered" from "the tool ran and failed" — a distinction the model acts on and a trace has to
 * show. Opening a scope rather than only starting the observation also parents whatever the
 * executor does downstream (the MCP round trip and its HTTP client span) under this span instead
 * of under Spring AI's.
 *
 * <p>The parent is handed in at construction rather than read from the registry inside
 * {@link #call(String)}, and that is the difference between one trace and two. Spring AI's
 * {@code ToolCallingAdvisor} runs the tool-execution branch of a streamed turn on
 * {@code Schedulers.boundedElastic()}, and automatic Reactor context propagation is off on this
 * classpath, so by the time {@code call} runs there is no observation on the thread local and this
 * span would open as a root — the tool call and the MCP round trip landing in Tempo under a trace
 * id of their own, detached from the turn that caused them. The callbacks are built eagerly, on the
 * thread that still holds the turn's scope, so capturing the parent there is what keeps the two
 * together.
 */
class ToolCallbackAdapter implements ToolCallback {

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() { };

    private static final String ERROR_PREFIX = "Error: ";

    private final ToolSpecification specification;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Observation callerObservation;

    ToolCallbackAdapter(ToolSpecification specification, ToolExecutor toolExecutor, ObjectMapper objectMapper,
            ObservationRegistry observationRegistry, Observation callerObservation) {
        this.specification = specification;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.callerObservation = callerObservation;
    }

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(specification.name())
                .description(specification.description())
                .inputSchema(objectMapper.writeValueAsString(specification.inputSchema()))
                .build();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        Observation observation = toolResultObservation().start();
        try (Observation.Scope scope = observation.openScope()) {
            Map<String, Object> arguments = objectMapper.readValue(toolInput, ARGUMENTS_TYPE);
            ToolExecutionResult result = toolExecutor.execute(specification.name(), arguments);
            String text = result.error() ? ERROR_PREFIX + result.text() : result.text();
            observation.lowCardinalityKeyValue(AssistantObservations.KEY_OUTCOME, result.error()
                    ? AssistantMetrics.OUTCOME_FAILURE
                    : AssistantMetrics.OUTCOME_SUCCESS);
            observation.highCardinalityKeyValue(AssistantObservations.KEY_TOOL_RESULT_CHARS,
                    String.valueOf(text != null ? text.length() : 0));
            return text;
        } catch (RuntimeException error) {
            // A port or protocol failure, as opposed to the tool answering with error=true.
            observation.lowCardinalityKeyValue(AssistantObservations.KEY_OUTCOME, AssistantMetrics.OUTCOME_FAILURE);
            observation.error(error);
            throw error;
        } finally {
            observation.stop();
        }
    }

    /**
     * The unstarted tool-result observation, with the caller's turn pinned as its parent when there
     * was one. {@code parentObservation(null)} would clear the parent instead of leaving whatever
     * the registry happened to hold, so the null case is skipped rather than passed through.
     */
    private Observation toolResultObservation() {
        Observation observation =
                Observation.createNotStarted(AssistantObservations.TOOL_RESULT, observationRegistry)
                        .contextualName(AssistantObservations.CONTEXTUAL_NAME_TOOL_RESULT)
                        .lowCardinalityKeyValue(AssistantObservations.KEY_BLOCK_TYPE,
                                AssistantObservations.BLOCK_TYPE_TOOL_RESULT)
                        .highCardinalityKeyValue(AssistantObservations.KEY_TOOL_NAME, specification.name());
        return callerObservation == null ? observation : observation.parentObservation(callerObservation);
    }
}
