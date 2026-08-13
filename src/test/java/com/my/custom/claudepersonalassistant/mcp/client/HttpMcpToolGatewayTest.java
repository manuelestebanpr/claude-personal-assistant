package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins what the outbound adapter actually puts on the wire.
 *
 * <p>The point of revision {@code 2026-07-28} is that a client has nothing to set up: these tests
 * assert that discovering and calling a tool each cost exactly one request, with no
 * {@code initialize} and no {@code notifications/initialized} in front of them.
 */
class HttpMcpToolGatewayTest {

    private static final String URL = "http://localhost:8080/mcp";

    private MockRestServiceServer server;
    private HttpMcpToolGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        gateway = new HttpMcpToolGateway(builder.build(), objectMapper);
    }

    @Test
    void discoversToolsInASingleRequestWithNoHandshake() {
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION))
                .andExpect(header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST))
                .andExpect(header("Accept", "application/json, text/event-stream"))
                .andExpect(jsonPath("$.method").value(McpProtocol.METHOD_TOOLS_LIST))
                // Version, identity and capabilities travel with every message instead of being
                // negotiated once per session — that is what makes the transport stateless.
                .andExpect(jsonPath("$.params._meta['" + McpProtocol.META_PROTOCOL_VERSION + "']")
                        .value(McpProtocol.VERSION))
                .andExpect(jsonPath("$.params._meta['" + McpProtocol.META_CLIENT_INFO + "'].name").exists())
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
                          {"name":"get_current_hour","title":"Current hour","description":"Returns the time.",
                           "inputSchema":{"type":"object","additionalProperties":false}}]}}
                        """, MediaType.APPLICATION_JSON));

        List<ToolDescriptor> tools = gateway.listTools();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_current_hour");
            assertThat(tool.title()).isEqualTo("Current hour");
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
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                         "content":[{"type":"text","text":"The current time is 21:07 (Europe/Madrid)."}],
                         "isError":false}}
                        """, MediaType.APPLICATION_JSON));

        ToolResult result = gateway.callTool(ToolInvocation.of("get_current_hour"));

        assertThat(result).isEqualTo(ToolResult.ok("The current time is 21:07 (Europe/Madrid)."));
        server.verify();
    }

    @Test
    void surfacesAToolFailureAsAnErrorResultNotAnException() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                 "content":[{"type":"text","text":"clock unavailable"}],"isError":true}}
                """, MediaType.APPLICATION_JSON));

        ToolResult result = gateway.callTool(ToolInvocation.of("get_current_hour"));

        assertThat(result).isEqualTo(ToolResult.failed("clock unavailable"));
    }

    @Test
    void raisesAProtocolErrorAsAClientException() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: nope"}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.callTool(ToolInvocation.of("nope")))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("-32602")
                .hasMessageContaining("Unknown tool");
    }

    @Test
    void wrapsATransportFailure() {
        server.expect(requestTo(URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());

        assertThatThrownBy(() -> gateway.listTools())
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(McpProtocol.METHOD_TOOLS_LIST);
    }

    @Test
    void passesToolArgumentsThrough() {
        server.expect(requestTo(URL))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.params.arguments.zone").value("UTC"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                         "content":[{"type":"text","text":"ok"}],"isError":false}}
                        """, MediaType.APPLICATION_JSON));

        gateway.callTool(new ToolInvocation("get_current_hour", Map.of("zone", "UTC")));

        server.verify();
    }
}
