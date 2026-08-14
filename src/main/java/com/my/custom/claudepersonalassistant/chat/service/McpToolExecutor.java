package com.my.custom.claudepersonalassistant.chat.service;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

/**
 * Adapts {@link McpToolGateway} to the assistant module's {@link ToolExecutor} port — the
 * automatic counterpart to the manual palette in {@link DefaultChatFacade#executeTool}. Reusing
 * the gateway means a model-initiated call gets the same {@code ToolInvokedEvent}/metrics as a
 * manual one, for free.
 */
@Component
@RequiredArgsConstructor
class McpToolExecutor implements ToolExecutor {

    private final McpToolGateway toolGateway;

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments) {
        ToolResult result = toolGateway.callTool(new ToolInvocation(toolName, arguments));
        return new ToolExecutionResult(result.text(), result.error());
    }
}
