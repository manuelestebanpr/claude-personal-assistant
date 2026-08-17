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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The stateless MCP revision {@code 2026-07-28}: every request is a self-contained POST, with no
 * handshake to complete first.
 */
class StatelessWireClientTest {

    private static final String URL = "https://example.test/mcp";
    private static final String TOOLS_RESULT = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"a","inputSchema":{"type":"object"}}]}}
            """;

    private MockRestServiceServer server;
    private StatelessWireClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StatelessWireClient(builder.build(), URL);
    }

    @Test
    void sendsOneSelfDescribingRequestWithNoHandshake() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.jsonrpc").value(McpProtocol.JSONRPC_VERSION))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.method").value(McpProtocol.METHOD_TOOLS_LIST))
                .andExpect(jsonPath("$.params._meta['io.modelcontextprotocol/protocolVersion']")
                        .value(McpProtocol.VERSION))
                .andExpect(jsonPath("$.params._meta['io.modelcontextprotocol/clientInfo'].name")
                        .value("claude-personal-assistant"))
                .andExpect(header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION))
                .andExpect(header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST))
                .andRespond(withSuccess(TOOLS_RESULT, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of());

        assertThat(response.result()).isNotNull();
        server.verify();
    }

    /**
     * Pins the same message-composition fix as {@code SessionWireClientTest}: the underlying
     * transport failure's reason (e.g. an upstream 403 from an OAuth scope problem) must survive
     * into the thrown {@link McpClientException}'s message, not just its cause.
     */
    @Test
    void includesTheUnderlyingTransportFailureReasonWhenARequestFails() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.send(McpProtocol.METHOD_TOOLS_LIST, null, Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("MCP request")
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).isNotNull();
                    assertThat(thrown.getMessage()).contains(thrown.getCause().getMessage());
                });
    }
}
