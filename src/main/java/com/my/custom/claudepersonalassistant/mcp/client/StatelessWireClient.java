package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

/**
 * Speaks MCP revision {@code 2026-07-28}.
 *
 * <p>There is no connection to open and no handshake to complete: each call is one self-describing
 * POST carrying its own {@code _meta}, so the first request a caller makes can be the useful one.
 */
class StatelessWireClient implements McpWireClient {

    private static final Map<String, Object> CLIENT_INFO =
            Map.of("name", "claude-personal-assistant", "version", "1.0.0");

    private final RestClient restClient;
    private final String url;
    private final AtomicLong requestIds = new AtomicLong();

    StatelessWireClient(RestClient restClient, String url) {
        this.restClient = restClient;
        this.url = url;
    }

    @Override
    public JsonRpcResponse send(String method, String toolName, Map<String, Object> params) {
        Map<String, Object> body = Map.of(
                "jsonrpc", McpProtocol.JSONRPC_VERSION,
                "id", requestIds.incrementAndGet(),
                "method", method,
                "params", withMeta(params));
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(url)
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
        }
        catch (RestClientException transportFailure) {
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
}
