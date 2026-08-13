package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.CallToolResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;
import com.my.custom.claudepersonalassistant.mcp.protocol.ListToolsResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;
import com.my.custom.claudepersonalassistant.mcp.protocol.TextContent;
import com.my.custom.claudepersonalassistant.mcp.protocol.ToolDefinition;

/**
 * Reaches an MCP server over Streamable HTTP as defined by revision {@code 2026-07-28}.
 *
 * <p>There is no connection to open and no handshake to complete: each call is one self-describing
 * POST carrying its own {@code _meta}, so the first request a caller makes can be the useful one.
 */
@Component
class HttpMcpToolGateway implements McpToolGateway {

    private static final Map<String, Object> CLIENT_INFO =
            Map.of("name", "claude-personal-assistant", "version", "1.0.0");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIds = new AtomicLong();

    HttpMcpToolGateway(RestClient mcpRestClient, ObjectMapper objectMapper) {
        this.restClient = mcpRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolDescriptor> listTools() {
        JsonRpcResponse response = send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());
        ListToolsResult result = convert(response, ListToolsResult.class);
        return result.tools().stream().map(this::toDescriptor).toList();
    }

    @Override
    public ToolResult callTool(ToolInvocation invocation) {
        Map<String, Object> params = Map.of(
                McpProtocol.PARAM_NAME, invocation.name(),
                McpProtocol.PARAM_ARGUMENTS, invocation.arguments());
        JsonRpcResponse response = send(McpProtocol.METHOD_TOOLS_CALL, invocation.name(), params);
        CallToolResult result = convert(response, CallToolResult.class);
        return new ToolResult(textOf(result), result.isError());
    }

    private JsonRpcResponse send(String method, String toolName, Map<String, Object> params) {
        Map<String, Object> body = Map.of(
                "jsonrpc", McpProtocol.JSONRPC_VERSION,
                "id", requestIds.incrementAndGet(),
                "method", method,
                "params", withMeta(params));
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    // Both are required of a client: the server chooses per request whether to
                    // answer with a single JSON object or a request-scoped SSE stream.
                    .header("Accept", "application/json, text/event-stream")
                    .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION)
                    .header(McpProtocol.HEADER_METHOD, method);
            if (toolName != null) {
                request = request.header(McpProtocol.HEADER_NAME, toolName);
            }
            return request.body(body).retrieve().body(JsonRpcResponse.class);
        } catch (RestClientException transportFailure) {
            throw new McpClientException("MCP request '%s' failed".formatted(method), transportFailure);
        }
    }

    /**
     * Every request carries the protocol version, client identity and capabilities inline. In
     * earlier revisions these were negotiated once per session; without sessions they travel with
     * each message, which is exactly what makes the transport stateless.
     */
    private Map<String, Object> withMeta(Map<String, Object> params) {
        Map<String, Object> withMeta = new LinkedHashMap<>(params);
        withMeta.put(McpProtocol.PARAM_META, Map.of(
                McpProtocol.META_PROTOCOL_VERSION, McpProtocol.VERSION,
                McpProtocol.META_CLIENT_INFO, CLIENT_INFO,
                McpProtocol.META_CLIENT_CAPABILITIES, Map.of()));
        return withMeta;
    }

    private <T> T convert(JsonRpcResponse response, Class<T> type) {
        if (response == null) {
            throw new McpClientException("MCP server returned an empty response");
        }
        if (response.error() != null) {
            throw new McpClientException("MCP server returned error %d: %s"
                    .formatted(response.error().code(), response.error().message()));
        }
        try {
            return objectMapper.convertValue(response.result(), type);
        } catch (RuntimeException malformed) {
            throw new McpClientException("MCP server returned an unreadable result", malformed);
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
        return new ToolDescriptor(definition.name(), definition.title(), definition.description(),
                definition.inputSchema() == null ? Map.of() : definition.inputSchema());
    }
}
