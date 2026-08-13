package com.my.custom.claudepersonalassistant.mcp.domain;

import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;


import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvocationRejectedEvent;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvokedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DefaultToolRegistryTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ToolEventPublisher eventPublisher = mock(ToolEventPublisher.class);

    @Test
    void keepsToolsInADeterministicOrder() {
        // The specification asks for a stable order so clients can cache the list and model
        // prompt caches keep hitting; bean discovery order is not stable.
        DefaultToolRegistry registry = registryOf(stub("zulu", "z"), stub("alpha", "a"));

        assertThat(registry.tools()).extracting(McpTool::name).containsExactly("alpha", "zulu");
    }

    @Test
    void invokesAToolAndPublishesTheOutcome() {
        DefaultToolRegistry registry = registryOf(stub("alpha", "done"));

        ToolResult result = registry.invoke("alpha", Map.of());

        assertThat(result).isEqualTo(ToolResult.ok("done"));
        verify(eventPublisher).publish(new ToolInvokedEvent("alpha", false));
        assertThat(counter("alpha", McpMetrics.OUTCOME_SUCCESS)).isEqualTo(1.0);
    }

    /** A tool that ran and failed is a business outcome, not a protocol error. */
    @Test
    void turnsAToolFailureIntoAnErrorResult() {
        DefaultToolRegistry registry = registryOf(failing("alpha", "upstream unavailable"));

        ToolResult result = registry.invoke("alpha", Map.of());

        assertThat(result).isEqualTo(ToolResult.failed("upstream unavailable"));
        verify(eventPublisher).publish(new ToolInvokedEvent("alpha", true));
        assertThat(counter("alpha", McpMetrics.OUTCOME_FAILURE)).isEqualTo(1.0);
    }

    @Test
    void rejectsAnUnknownTool() {
        DefaultToolRegistry registry = registryOf(stub("alpha", "a"));

        assertThatThrownBy(() -> registry.invoke("nope", Map.of()))
                .isInstanceOf(UnknownToolException.class)
                .hasMessageContaining("nope");
        verify(eventPublisher).publish(new ToolInvocationRejectedEvent("nope"));
    }

    /** Two tools under one name would make tools/call silently ambiguous. */
    @Test
    void refusesToStartWithDuplicateToolNames() {
        assertThatThrownBy(() -> registryOf(stub("alpha", "a"), stub("alpha", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha");
    }

    private double counter(String tool, String outcome) {
        return meterRegistry.counter(McpMetrics.TOOL_INVOCATIONS,
                McpMetrics.TAG_TOOL, tool, McpMetrics.TAG_OUTCOME, outcome).count();
    }

    private DefaultToolRegistry registryOf(McpTool... tools) {
        return new DefaultToolRegistry(List.of(tools), eventPublisher, meterRegistry);
    }

    private McpTool stub(String name, String output) {
        return tool(name, arguments -> output);
    }

    private McpTool failing(String name, String reason) {
        return tool(name, arguments -> {
            throw new ToolExecutionException(reason);
        });
    }

    private McpTool tool(String name, java.util.function.Function<Map<String, Object>, String> behaviour) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String title() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return behaviour.apply(arguments);
            }
        };
    }
}
