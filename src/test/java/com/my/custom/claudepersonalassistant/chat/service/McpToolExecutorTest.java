package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Adapts {@link McpToolGateway} to the assistant module's {@code ToolExecutor} port — the
 * automatic counterpart to the manual picker in {@link DefaultChatFacade#executeTool}.
 *
 * <p>The model names a tool but never a server, so most of what is asserted here is the routing
 * that fills the gap.
 */
class McpToolExecutorTest {

    private final McpToolGateway toolGateway = mock(McpToolGateway.class);
    private final McpToolExecutor executor = new McpToolExecutor(toolGateway);

    @BeforeEach
    void stubToolCatalogue() {
        given(toolGateway.listTools()).willReturn(List.of(
                new ToolDescriptor("local", "Local", "get_current_hour", "Current hour",
                        "Returns the time.", Map.of()),
                new ToolDescriptor("weather", "Weather", "forecast", "Forecast",
                        "Returns the forecast.", Map.of())));
    }

    @Test
    void invokesTheGatewayAndReturnsItsTextOnSuccess() {
        given(toolGateway.callTool(new ToolInvocation("local", "get_current_hour", Map.of("zone", "Europe/Madrid"))))
                .willReturn(ToolResult.ok("21:07"));

        ToolExecutionResult result = executor.execute("get_current_hour", Map.of("zone", "Europe/Madrid"));

        assertThat(result).isEqualTo(ToolExecutionResult.ok("21:07"));
    }

    @Test
    void carriesTheGatewaysBusinessFailureThroughUnchanged() {
        given(toolGateway.callTool(new ToolInvocation("local", "get_current_hour", Map.of())))
                .willReturn(ToolResult.failed("unknown timezone"));

        ToolExecutionResult result = executor.execute("get_current_hour", Map.of());

        assertThat(result).isEqualTo(ToolExecutionResult.failed("unknown timezone"));
    }

    /** A bare tool name has to reach the server that actually offers it, not the first configured. */
    @Test
    void routesEachToolToTheServerThatOffersIt() {
        given(toolGateway.callTool(new ToolInvocation("weather", "forecast", Map.of())))
                .willReturn(ToolResult.ok("sunny"));

        assertThat(executor.execute("forecast", Map.of())).isEqualTo(ToolExecutionResult.ok("sunny"));

        verify(toolGateway).callTool(new ToolInvocation("weather", "forecast", Map.of()));
    }

    /** Resolving means asking every server for its catalogue; doing that per call would be waste. */
    @Test
    void resolvesAToolsServerOnceAndReusesIt() {
        given(toolGateway.callTool(new ToolInvocation("local", "get_current_hour", Map.of())))
                .willReturn(ToolResult.ok("21:07"));

        executor.execute("get_current_hour", Map.of());
        executor.execute("get_current_hour", Map.of());

        verify(toolGateway, times(1)).listTools();
        verify(toolGateway, times(2)).callTool(new ToolInvocation("local", "get_current_hour", Map.of()));
    }

    /** A route can go stale — a server restarts, a tool moves — so a failure must not stick. */
    @Test
    void forgetsARouteThatFailed() {
        willThrow(new McpClientException("connection refused"))
                .given(toolGateway).callTool(new ToolInvocation("local", "get_current_hour", Map.of()));

        assertThatThrownBy(() -> executor.execute("get_current_hour", Map.of()))
                .isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> executor.execute("get_current_hour", Map.of()))
                .isInstanceOf(McpClientException.class);

        verify(toolGateway, times(2)).listTools();
    }

    @Test
    void saysSoWhenNoConnectedServerOffersTheTool() {
        assertThatThrownBy(() -> executor.execute("nonexistent", Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("nonexistent");
    }
}
