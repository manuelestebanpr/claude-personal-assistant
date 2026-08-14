package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins what a connection to a {@code STATELESS} server actually puts on the wire.
 *
 * <p>The point of revision {@code 2026-07-28} is that a client has nothing to set up: these tests
 * assert that discovering and calling a tool each cost exactly one request, with no
 * {@code initialize} and no {@code notifications/initialized} in front of them.
 */
class McpServerConnectionTest {

    private static final String URL = "http://localhost:8080/mcp";

    private MockRestServiceServer server;
    private McpServerConnection connection;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        McpProperties.Server configured = new McpProperties.Server("local", "Local", "http://localhost:8080",
                "/mcp", McpProperties.Protocol.STATELESS, null);
        connection = new McpServerConnection(configured,
                new StatelessWireClient(builder.build(), URL), JsonMapper.builder().build());
    }

    @Test
    void discoversToolsInASingleRequestWithNoHandshake() {
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION))
                .andExpect(header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST))
                .andExpect(header("Accept", "application/json, text/event-stream"))
                .andExpect(jsonPath("$.params._meta['" + McpProtocol.META_PROTOCOL_VERSION + "']")
                        .value(McpProtocol.VERSION))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
                          {"name":"get_current_hour","title":"Current hour","description":"Returns the time.",
                           "inputSchema":{"type":"object","additionalProperties":false}}]}}
                        """, MediaType.APPLICATION_JSON));

        List<ToolDescriptor> tools = connection.listTools();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_current_hour");
            // Every tool remembers where it came from, or a call could not be routed back.
            assertThat(tool.serverId()).isEqualTo("local");
            assertThat(tool.serverName()).isEqualTo("Local");
            assertThat(tool.takesNoArguments()).isTrue();
        });
        // verify() fails if any extra request was made, which is the assertion that no
        // initialize/initialized exchange happened.
        server.verify();
    }

    @Test
    void callsAToolMirroringItsNameIntoTheHeader() {
        server.expect(requestTo(URL))
                .andExpect(header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_CALL))
                .andExpect(header(McpProtocol.HEADER_NAME, "get_current_hour"))
                .andExpect(jsonPath("$.params.name").value("get_current_hour"))
                .andExpect(jsonPath("$.params.arguments.zone").value("UTC"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                         "content":[{"type":"text","text":"21:07 (UTC)."}],"isError":false}}
                        """, MediaType.APPLICATION_JSON));

        ToolResult result = connection.callTool("get_current_hour", Map.of("zone", "UTC"));

        assertThat(result).isEqualTo(ToolResult.ok("21:07 (UTC)."));
        server.verify();
    }

    @Test
    void surfacesAToolFailureAsAnErrorResultNotAnException() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                 "content":[{"type":"text","text":"clock unavailable"}],"isError":true}}
                """, MediaType.APPLICATION_JSON));

        assertThat(connection.callTool("get_current_hour", Map.of()))
                .isEqualTo(ToolResult.failed("clock unavailable"));
    }

    @Test
    void raisesAProtocolErrorAsAClientExceptionNamingTheServer() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: nope"}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connection.callTool("nope", Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("local")
                .hasMessageContaining("-32602");
    }

    /** A server that is down is a fact to report, not an exception for the picker to handle. */
    @Test
    void describesAnUnreachableServerInsteadOfThrowing() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        McpServerDescriptor described = connection.describe();

        assertThat(described.id()).isEqualTo("local");
        assertThat(described.reachable()).isFalse();
        assertThat(described.toolCount()).isZero();
        assertThat(described.detail()).isNotBlank();
    }

    @Test
    void describesAReachableServerWithItsToolCount() {
        server.expect(requestTo(URL)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
                          {"name":"a","inputSchema":{"type":"object"}},
                          {"name":"b","inputSchema":{"type":"object"}}]}}
                        """, MediaType.APPLICATION_JSON));

        McpServerDescriptor described = connection.describe();

        assertThat(described.reachable()).isTrue();
        assertThat(described.toolCount()).isEqualTo(2);
        assertThat(described.protocol()).isEqualTo("STATELESS");
        assertThat(described.detail()).isNull();
    }
}
