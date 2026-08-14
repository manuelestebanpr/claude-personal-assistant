package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;
import com.my.custom.claudepersonalassistant.mcp.protocol.CallToolResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;
import com.my.custom.claudepersonalassistant.mcp.protocol.ListToolsResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;
import com.my.custom.claudepersonalassistant.mcp.protocol.TextContent;
import com.my.custom.claudepersonalassistant.mcp.protocol.ToolDefinition;

/**
 * One configured MCP server, and everything that is the same whichever revision it speaks.
 *
 * <p>Reading a tool list, calling a tool and unpacking the result are revision-independent; the
 * differences live behind {@link McpWireClient}. Splitting it this way is what lets our own server
 * and a third-party one sit side by side in the same picker.
 */
public class McpServerConnection {

    private final McpProperties.Server server;
    private final McpWireClient wire;
    private final ObjectMapper objectMapper;

    McpServerConnection(McpProperties.Server server, McpWireClient wire, ObjectMapper objectMapper) {
        this.server = server;
        this.wire = wire;
        this.objectMapper = objectMapper;
    }

    public String id() {
        return server.id();
    }

    /**
     * Asks the server what it offers and reports how that went.
     *
     * <p>Swallows the failure on purpose: a server being down is a fact to show the user, not a
     * reason for the whole picker to fail.
     */
    public McpServerDescriptor describe() {
        try {
            List<ToolDescriptor> tools = listTools();
            return new McpServerDescriptor(server.id(), server.displayName(), server.url(),
                    server.protocol().name(), true, tools.size(), null);
        }
        catch (McpClientException unreachable) {
            return new McpServerDescriptor(server.id(), server.displayName(), server.url(),
                    server.protocol().name(), false, 0, unreachable.getMessage());
        }
    }

    public List<ToolDescriptor> listTools() {
        JsonRpcResponse response = wire.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());
        ListToolsResult result = convert(response, ListToolsResult.class);
        if (result.tools() == null) {
            return List.of();
        }
        return result.tools().stream().map(this::toDescriptor).toList();
    }

    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = Map.of(
                McpProtocol.PARAM_NAME, toolName,
                McpProtocol.PARAM_ARGUMENTS, arguments);
        JsonRpcResponse response = wire.send(McpProtocol.METHOD_TOOLS_CALL, toolName, params);
        CallToolResult result = convert(response, CallToolResult.class);
        return new ToolResult(textOf(result), result.isError());
    }

    private <T> T convert(JsonRpcResponse response, Class<T> type) {
        if (response == null) {
            throw new McpClientException("MCP server '%s' returned an empty response".formatted(server.id()));
        }
        if (response.error() != null) {
            throw new McpClientException("MCP server '%s' returned error %d: %s"
                    .formatted(server.id(), response.error().code(), response.error().message()));
        }
        try {
            return objectMapper.convertValue(response.result(), type);
        }
        catch (RuntimeException malformed) {
            throw new McpClientException(
                    "MCP server '%s' returned an unreadable result".formatted(server.id()), malformed);
        }
    }

    private String textOf(CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
                .map(TextContent::text)
                .filter(text -> text != null && !text.isBlank())
                .reduce((first, second) -> first + "\n" + second)
                .orElse("");
    }

    private ToolDescriptor toDescriptor(ToolDefinition definition) {
        return new ToolDescriptor(server.id(), server.displayName(), definition.name(),
                definition.title(), definition.description(),
                definition.inputSchema() == null ? Map.of() : definition.inputSchema());
    }
}
