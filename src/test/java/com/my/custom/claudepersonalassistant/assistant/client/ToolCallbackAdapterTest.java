package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final ToolSpecification specification = new ToolSpecification("get_current_hour", "Returns the time.",
            Map.of("type", "object", "additionalProperties", false));
    private final ToolCallbackAdapter adapter = new ToolCallbackAdapter(specification, toolExecutor, objectMapper);

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
}
