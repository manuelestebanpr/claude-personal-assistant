package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;
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

    static final String REACHABLE_MESSAGE = "MCP server reachable";
    static final String UNREACHABLE_MESSAGE = "MCP server unreachable";

    static final String KEY_SERVER = "server";
    static final String KEY_CATEGORY = "category";
    static final String KEY_REACHABLE = "reachable";
    static final String KEY_TOOL_COUNT = "toolCount";
    static final String KEY_DETAIL = "detail";

    static final String CATEGORY_LOCAL = "local";
    static final String CATEGORY_REMOTE = "remote";

    private static final Logger log = LoggerFactory.getLogger(McpServerConnection.class);

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
            // DEBUG, not INFO: every GET of the server picker describes every configured server, so
            // at INFO the success path alone would fill the log with a line per server per page view.
            log.atDebug()
                    .addKeyValue(KEY_SERVER, server.id())
                    .addKeyValue(KEY_CATEGORY, category())
                    .addKeyValue(KEY_REACHABLE, true)
                    .addKeyValue(KEY_TOOL_COUNT, tools.size())
                    .log(REACHABLE_MESSAGE);
            return new McpServerDescriptor(server.id(), server.displayName(), server.url(),
                    server.protocol().name(), server.isLocal(), true, tools.size(), null);
        }
        catch (McpClientException | ToolExecutionException unreachable) {
            // The signal an auth/network/protocol-mismatch problem shows up as — e.g. a rejected
            // Google OAuth token, or a wrong SESSION-vs-STATELESS guess for a third-party server.
            // ToolExecutionException specifically covers a google-auth server's token-refresh
            // interceptor, which runs on every outgoing request — including this discovery call,
            // not just an actual tool invocation — and must not crash the whole picker.
            log.atWarn()
                    .addKeyValue(KEY_SERVER, server.id())
                    .addKeyValue(KEY_CATEGORY, category())
                    .addKeyValue(KEY_REACHABLE, false)
                    .addKeyValue(KEY_TOOL_COUNT, 0)
                    // Truncated for the log only: an unauthorised remote server answers 403 with its
                    // whole tool catalogue attached, and Loki drops the entire entry rather than the
                    // oversized field. The descriptor below keeps the full text, because that goes
                    // to the picker rather than to the backend.
                    .addKeyValue(KEY_DETAIL, FailureDetail.of(unreachable))
                    .log(UNREACHABLE_MESSAGE);
            return new McpServerDescriptor(server.id(), server.displayName(), server.url(),
                    server.protocol().name(), server.isLocal(), false, 0, unreachable.getMessage());
        }
    }

    private String category() {
        return server.isLocal() ? CATEGORY_LOCAL : CATEGORY_REMOTE;
    }

    public List<ToolDescriptor> listTools() {
        JsonRpcResponse response = wire.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());
        ListToolsResult result = unwrapResult(response, ListToolsResult.class);
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
        CallToolResult result = unwrapResult(response, CallToolResult.class);
        return new ToolResult(textOf(result), result.isError());
    }

    /** Reads the {@code result} member out, or fails: an empty body and a JSON-RPC error both throw. */
    private <T> T unwrapResult(JsonRpcResponse response, Class<T> type) {
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
