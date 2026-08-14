package com.my.custom.claudepersonalassistant.chat.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

/**
 * Adapts {@link McpToolGateway} to the assistant module's {@link ToolExecutor} port — the
 * automatic counterpart to the manual picker in {@link DefaultChatFacade#executeTool}. Reusing
 * the gateway means a model-initiated call gets the same {@code ToolInvokedEvent}/metrics as a
 * manual one, for free.
 *
 * <p>The model names a tool but not a server — it is given one flat catalogue and has no vocabulary
 * for where a tool came from — so the server has to be resolved here.
 */
@Component
@RequiredArgsConstructor
class McpToolExecutor implements ToolExecutor {

    private final McpToolGateway toolGateway;

    /**
     * Tool name to server id. Memoised because resolving means asking every server for its
     * catalogue, and the model may call several tools per turn.
     */
    private final Map<String, String> routes = new ConcurrentHashMap<>();

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments) {
        String serverId = routes.computeIfAbsent(toolName, this::resolve);
        try {
            ToolResult result = toolGateway.callTool(new ToolInvocation(serverId, toolName, arguments));
            return new ToolExecutionResult(result.text(), result.error());
        }
        catch (McpClientException failed) {
            // The route may simply have gone stale — a server restarted, or a tool moved. Drop it
            // so the next attempt resolves again rather than failing the same way forever.
            routes.remove(toolName);
            throw failed;
        }
    }

    private String resolve(String toolName) {
        return toolGateway.listTools().stream()
                .filter(tool -> tool.name().equals(toolName))
                .map(ToolDescriptor::serverId)
                .findFirst()
                .orElseThrow(() -> new McpClientException(
                        "No connected MCP server offers a tool named '%s'".formatted(toolName)));
    }
}
