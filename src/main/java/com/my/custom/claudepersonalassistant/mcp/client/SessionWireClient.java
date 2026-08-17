package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

/**
 * Speaks the session-based Streamable HTTP transport of MCP revisions up to {@code 2025-11-25} —
 * what most third-party servers still implement.
 *
 * <p>Three things this revision requires that {@link StatelessWireClient} does not: an
 * {@code initialize} exchange before any other method, a {@code notifications/initialized} that
 * acknowledges it, and an {@code Mcp-Session-Id} echoed on every later request. The handshake runs
 * once, lazily, and is re-run if the server forgets the session.
 */
class SessionWireClient implements McpWireClient {

    static final String PROTOCOL_VERSION = "2025-11-25";
    static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_INITIALIZED = "notifications/initialized";
    private static final Map<String, Object> CLIENT_INFO =
            Map.of("name", "claude-personal-assistant", "version", "1.0.0");

    private final RestClient restClient;
    private final String url;
    private final AtomicLong requestIds = new AtomicLong();
    private final ReentrantLock handshakeLock = new ReentrantLock();

    /** Null until the handshake completes; a server may legitimately never issue one. */
    private String sessionId;
    private boolean initialized;

    SessionWireClient(RestClient restClient, String url) {
        this.restClient = restClient;
        this.url = url;
    }

    @Override
    public JsonRpcResponse send(String method, String toolName, Map<String, Object> params) {
        initialize();
        try {
            return post(method, params).getBody();
        }
        catch (RestClientException transportFailure) {
            // A server that restarted has forgotten the session, and says so with a 404. One
            // re-handshake is worth trying before reporting the whole server as unreachable.
            if (staleSession(transportFailure)) {
                reset();
                initialize();
                return post(method, params).getBody();
            }
            throw new McpClientException(
                    "MCP request '%s' failed: %s".formatted(method, transportFailure.getMessage()),
                    transportFailure);
        }
    }

    private void initialize() {
        handshakeLock.lock();
        try {
            if (initialized) {
                return;
            }
            ResponseEntity<JsonRpcResponse> response = post(METHOD_INITIALIZE, Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", CLIENT_INFO));
            sessionId = response.getHeaders().getFirst(HEADER_SESSION_ID);
            initialized = true;
            // A notification carries no id and gets no result — the server answers 202. It is not
            // optional: a server may reject every later call until it arrives.
            notifyInitialized();
        }
        catch (RestClientException failed) {
            reset();
            throw new McpClientException(
                    "MCP handshake with %s failed: %s".formatted(url, failed.getMessage()), failed);
        }
        finally {
            handshakeLock.unlock();
        }
    }

    private void notifyInitialized() {
        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.set("Accept", "application/json, text/event-stream");
                    headers.set(McpProtocol.HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
                    if (sessionId != null) {
                        headers.set(HEADER_SESSION_ID, sessionId);
                    }
                })
                .body(Map.of("jsonrpc", McpProtocol.JSONRPC_VERSION, "method", METHOD_INITIALIZED))
                .retrieve()
                .toBodilessEntity();
    }

    private ResponseEntity<JsonRpcResponse> post(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        body.put("id", requestIds.incrementAndGet());
        body.put("method", method);
        body.put("params", params);
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.set("Accept", "application/json, text/event-stream");
                    headers.set(McpProtocol.HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
                    if (sessionId != null) {
                        headers.set(HEADER_SESSION_ID, sessionId);
                    }
                })
                .body(body)
                .retrieve()
                .toEntity(JsonRpcResponse.class);
    }

    private boolean staleSession(RestClientException failure) {
        return failure instanceof org.springframework.web.client.HttpClientErrorException.NotFound;
    }

    private void reset() {
        sessionId = null;
        initialized = false;
    }
}
