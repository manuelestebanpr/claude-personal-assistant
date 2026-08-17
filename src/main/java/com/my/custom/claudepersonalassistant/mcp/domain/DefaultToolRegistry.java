package com.my.custom.claudepersonalassistant.mcp.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvocationRejectedEvent;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvokedEvent;

/**
 * Indexes every {@link McpTool} bean by name, keeps them in a stable order, and turns an
 * invocation into a result, an event and a metric.
 */
@Component
class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, McpTool> toolsByName;
    private final ToolEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    DefaultToolRegistry(List<McpTool> tools, ToolEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.toolsByName = index(tools);
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<McpTool> tools() {
        return List.copyOf(toolsByName.values());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        McpTool tool = toolsByName.get(toolName);
        if (tool == null) {
            eventPublisher.publish(new ToolInvocationRejectedEvent(toolName));
            throw new UnknownToolException(toolName);
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ToolResult result = ToolResult.ok(tool.execute(arguments));
            recordToolMetrics(sample, toolName, McpMetrics.OUTCOME_SUCCESS);
            eventPublisher.publish(new ToolInvokedEvent(toolName, false));
            return result;
        } catch (ToolExecutionException failure) {
            recordToolMetrics(sample, toolName, McpMetrics.OUTCOME_FAILURE);
            eventPublisher.publish(new ToolInvokedEvent(toolName, true));
            return ToolResult.failed(failure.getMessage());
        }
    }

    private void recordToolMetrics(Timer.Sample sample, String toolName, String outcome) {
        sample.stop(Timer.builder(McpMetrics.TOOL_DURATION)
                .tag(McpMetrics.TAG_TOOL, toolName)
                .tag(McpMetrics.TAG_OUTCOME, outcome)
                .register(meterRegistry));
        meterRegistry.counter(McpMetrics.TOOL_INVOCATIONS,
                McpMetrics.TAG_TOOL, toolName,
                McpMetrics.TAG_OUTCOME, outcome).increment();
    }

    private Map<String, McpTool> index(List<McpTool> tools) {
        Map<String, McpTool> index = new LinkedHashMap<>();
        tools.stream().sorted(Comparator.comparing(McpTool::name)).forEach(tool -> {
            McpTool clash = index.putIfAbsent(tool.name(), tool);
            if (clash != null) {
                // Two tools answering to one name makes tools/call ambiguous, and the client has
                // no way to tell which it reached. Fail at startup rather than at call time.
                throw new IllegalStateException("Duplicate MCP tool name: " + tool.name());
            }
        });
        return index;
    }
}
