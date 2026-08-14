package com.my.custom.claudepersonalassistant.chat.service;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Adapts {@link McpToolGateway} to the assistant module's {@code ToolExecutor} port — the
 * automatic counterpart to the manual palette in {@link DefaultChatFacade#executeTool}.
 */
class McpToolExecutorTest {

    private final McpToolGateway toolGateway = mock(McpToolGateway.class);
    private final McpToolExecutor executor = new McpToolExecutor(toolGateway);

    @Test
    void invokesTheGatewayAndReturnsItsTextOnSuccess() {
        given(toolGateway.callTool(new ToolInvocation("get_current_hour", Map.of("zone", "Europe/Madrid"))))
                .willReturn(ToolResult.ok("21:07"));

        ToolExecutionResult result = executor.execute("get_current_hour", Map.of("zone", "Europe/Madrid"));

        assertThat(result).isEqualTo(ToolExecutionResult.ok("21:07"));
    }

    @Test
    void carriesTheGatewaySBusinessFailureThroughUnchanged() {
        given(toolGateway.callTool(new ToolInvocation("get_current_hour", Map.of())))
                .willReturn(ToolResult.failed("unknown timezone"));

        ToolExecutionResult result = executor.execute("get_current_hour", Map.of());

        assertThat(result).isEqualTo(ToolExecutionResult.failed("unknown timezone"));
    }

    @Test
    void invokesTheGatewayWithExactlyOneCall() {
        given(toolGateway.callTool(new ToolInvocation("get_current_hour", Map.of())))
                .willReturn(ToolResult.ok("21:07"));

        executor.execute("get_current_hour", Map.of());

        verify(toolGateway).callTool(new ToolInvocation("get_current_hour", Map.of()));
    }
}
