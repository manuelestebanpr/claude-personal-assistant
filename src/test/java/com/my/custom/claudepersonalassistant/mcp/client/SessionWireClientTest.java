package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The other half of being a real MCP client: most third-party servers still expect a session to be
 * opened before they will answer anything.
 */
class SessionWireClientTest {

    private static final String URL = "https://example.test/mcp";
    private static final String TOOLS_RESULT = """
            {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a","inputSchema":{"type":"object"}}]}}
            """;

    private MockRestServiceServer server;
    private SessionWireClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SessionWireClient(builder.build(), URL);
    }

    /**
     * Three requests for the first call, not one: {@code initialize}, the
     * {@code notifications/initialized} that acknowledges it, and only then the useful one.
     */
    @Test
    void opensASessionBeforeItsFirstRealRequest() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.method").value("initialize"))
                .andExpect(jsonPath("$.params.protocolVersion").value(SessionWireClient.PROTOCOL_VERSION))
                .andExpect(jsonPath("$.params.clientInfo.name").value("claude-personal-assistant"))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                        MediaType.APPLICATION_JSON)
                        .header(SessionWireClient.HEADER_SESSION_ID, "session-42"));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.method").value("notifications/initialized"))
                // A notification carries no id; a server may reject one that does.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(header(SessionWireClient.HEADER_SESSION_ID, "session-42"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.method").value(McpProtocol.METHOD_TOOLS_LIST))
                .andExpect(header(SessionWireClient.HEADER_SESSION_ID, "session-42"))
                .andExpect(header(McpProtocol.HEADER_PROTOCOL_VERSION, SessionWireClient.PROTOCOL_VERSION))
                .andRespond(withSuccess(TOOLS_RESULT, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());

        assertThat(response.result()).isNotNull();
        server.verify();
    }

    @Test
    void opensTheSessionOnlyOnce() {
        expectHandshake("session-42");
        server.expect(requestTo(URL)).andRespond(withSuccess(TOOLS_RESULT, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(TOOLS_RESULT, MediaType.APPLICATION_JSON));

        client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());
        client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());

        server.verify();
    }

    /** A server that never issues a session id is fine — the header is simply not echoed. */
    @Test
    void toleratesAServerThatIssuesNoSessionId() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist(SessionWireClient.HEADER_SESSION_ID))
                .andRespond(withSuccess(TOOLS_RESULT, MediaType.APPLICATION_JSON));

        client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());

        server.verify();
    }

    /**
     * A restarted server has forgotten the session and says so with a 404. Re-handshaking once
     * beats reporting the whole server as unreachable until the page is reloaded.
     */
    @Test
    void reopensTheSessionWhenTheServerHasForgottenIt() {
        expectHandshake("session-42");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        expectHandshake("session-43");
        server.expect(requestTo(URL))
                .andExpect(header(SessionWireClient.HEADER_SESSION_ID, "session-43"))
                .andRespond(withSuccess(TOOLS_RESULT, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());

        assertThat(response.result()).isNotNull();
        server.verify();
    }

    @Test
    void reportsAFailedHandshakeAgainstTheServerSUrl() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("handshake")
                .hasMessageContaining(URL)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains(thrown.getCause().getMessage()));
    }

    /**
     * The real bug this pins: the underlying transport failure's reason used to be swallowed,
     * leaving only "MCP handshake with <url> failed" with no indication of what actually went
     * wrong (e.g. an upstream 403 from an OAuth scope problem).
     */
    @Test
    void includesTheUnderlyingTransportFailureReasonWhenTheHandshakeFails() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of()))
                .isInstanceOf(McpClientException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).isNotNull();
                    assertThat(thrown.getMessage()).contains(thrown.getCause().getMessage());
                });
    }

    /**
     * Same swallowed-cause bug, but on the post-handshake request path rather than the handshake
     * itself.
     */
    @Test
    void includesTheUnderlyingTransportFailureReasonWhenARequestFailsAfterTheHandshake() {
        expectHandshake("session-42");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("MCP request")
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).isNotNull();
                    assertThat(thrown.getMessage()).contains(thrown.getCause().getMessage());
                });
    }

    private void expectHandshake(String sessionId) {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.method").value("initialize"))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                        MediaType.APPLICATION_JSON)
                        .header(SessionWireClient.HEADER_SESSION_ID, sessionId));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.method").value("notifications/initialized"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
    }
}
