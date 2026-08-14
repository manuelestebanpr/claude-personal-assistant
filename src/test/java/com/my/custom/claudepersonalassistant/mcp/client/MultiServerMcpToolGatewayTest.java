package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.api.UnknownMcpServerException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Fanning out across several servers, and the cases where one of them misbehaves. */
class MultiServerMcpToolGatewayTest {

    private final McpServerConnection local = connection("local");
    private final McpServerConnection weather = connection("weather");
    private final MultiServerMcpToolGateway gateway =
            new MultiServerMcpToolGateway(List.of(local, weather));

    @Test
    void listsEveryServerInConfigurationOrder() {
        given(local.describe()).willReturn(descriptor("local", true, 2, null));
        given(weather.describe()).willReturn(descriptor("weather", true, 1, null));

        assertThat(gateway.listServers()).extracting(McpServerDescriptor::id)
                .containsExactly("local", "weather");
    }

    @Test
    void offersTheModelEveryReachableServerSTools() {
        given(local.listTools()).willReturn(List.of(tool("local", "get_current_hour")));
        given(weather.listTools()).willReturn(List.of(tool("weather", "forecast")));

        assertThat(gateway.listTools()).extracting(ToolDescriptor::name)
                .containsExactly("get_current_hour", "forecast");
    }

    /** One server being down must not cost the model the tools of the others. */
    @Test
    void keepsTheOtherServersSToolsWhenOneIsUnreachable() {
        willThrow(new McpClientException("connection refused")).given(local).listTools();
        given(weather.listTools()).willReturn(List.of(tool("weather", "forecast")));

        assertThat(gateway.listTools()).extracting(ToolDescriptor::name).containsExactly("forecast");
    }

    /**
     * The model addresses a tool by bare name, so it has no way to say which server it meant.
     * First one configured wins, and the duplicate is dropped rather than silently shadowing it.
     */
    @Test
    void dropsADuplicateToolNameFromALaterServer() {
        given(local.listTools()).willReturn(List.of(tool("local", "search")));
        given(weather.listTools()).willReturn(List.of(tool("weather", "search")));

        assertThat(gateway.listTools()).singleElement()
                .satisfies(tool -> assertThat(tool.serverId()).isEqualTo("local"));
    }

    @Test
    void routesACallToTheServerNamedInTheInvocation() {
        given(weather.callTool("forecast", Map.of("city", "Bogota")))
                .willReturn(ToolResult.ok("sunny"));

        ToolResult result = gateway.callTool(
                new ToolInvocation("weather", "forecast", Map.of("city", "Bogota")));

        assertThat(result).isEqualTo(ToolResult.ok("sunny"));
        verify(weather).callTool("forecast", Map.of("city", "Bogota"));
    }

    @Test
    void namesTheConfiguredServersWhenAskedForOneThatDoesNotExist() {
        assertThatThrownBy(() -> gateway.callTool(ToolInvocation.of("nope", "forecast")))
                // A server that does not exist is the request's problem, not the server's — which
                // is what earns it a 404 rather than the 500 a bare McpClientException would get.
                .isInstanceOf(UnknownMcpServerException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("local");
    }

    /** Two servers under one id would make every call to it ambiguous. */
    @Test
    void refusesToStartWithDuplicateServerIds() {
        assertThatThrownBy(() -> new MultiServerMcpToolGateway(List.of(local, connection("local"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    private McpServerConnection connection(String id) {
        McpServerConnection connection = mock(McpServerConnection.class);
        given(connection.id()).willReturn(id);
        return connection;
    }

    private McpServerDescriptor descriptor(String id, boolean reachable, int toolCount, String detail) {
        return new McpServerDescriptor(id, id, "http://" + id + "/mcp", "STATELESS",
                reachable, toolCount, detail);
    }

    private ToolDescriptor tool(String serverId, String name) {
        return new ToolDescriptor(serverId, serverId, name, name, "Does a thing.", Map.of());
    }
}
