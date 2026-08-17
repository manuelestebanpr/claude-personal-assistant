package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.api.UnknownMcpServerException;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * Fans the gateway out across every configured server.
 *
 * <p>Connection order is configuration order, so the picker reads the way the file does.
 */
@Component
class MultiServerMcpToolGateway implements McpToolGateway {

    static final String DUPLICATE_TOOL_MESSAGE =
            "Duplicate tool name; keeping the first server's and dropping this one";
    /**
     * Deliberately distinct from the message the chat layer logs when the whole catalogue is
     * unavailable: one server dropping out and every server dropping out are different incidents,
     * and a shared body would make them one line in Loki.
     */
    static final String UNREACHABLE_SERVER_MESSAGE = "Skipping unreachable MCP server";

    static final String KEY_SERVER = "server";
    static final String KEY_TOOL = "tool";
    static final String KEY_DETAIL = "detail";

    private static final Logger log = LoggerFactory.getLogger(MultiServerMcpToolGateway.class);

    private final Map<String, McpServerConnection> connections;

    MultiServerMcpToolGateway(List<McpServerConnection> connections) {
        this.connections = index(connections);
    }

    @Override
    public List<McpServerDescriptor> listServers() {
        return connections.values().stream().map(McpServerConnection::describe).toList();
    }

    /**
     * The catalogue the model is offered: every reachable server's tools, flattened.
     *
     * <p>One unreachable server must not cost the model the tools of the others, so each is tried
     * independently and a failure is logged rather than propagated.
     */
    @Override
    public List<ToolDescriptor> listTools() {
        List<ToolDescriptor> aggregatedTools = new ArrayList<>();
        Set<String> claimedToolNames = new LinkedHashSet<>();
        for (McpServerConnection connection : connections.values()) {
            try {
                for (ToolDescriptor tool : connection.listTools()) {
                    if (claimedToolNames.add(tool.name())) {
                        aggregatedTools.add(tool);
                    }
                    else {
                        // The model addresses a tool by bare name, so it could not express which
                        // server it meant. First server configured wins.
                        log.atWarn()
                                .addKeyValue(KEY_TOOL, tool.name())
                                .addKeyValue(KEY_SERVER, connection.id())
                                .log(DUPLICATE_TOOL_MESSAGE);
                    }
                }
            }
            catch (McpClientException | ToolExecutionException unreachable) {
                // ToolExecutionException covers a google-auth server's token-refresh interceptor,
                // which fires on every outgoing request including this discovery call — treated
                // identically to an unreachable server so it doesn't cost the model every other
                // server's tools.
                // The reason travels as a key-value, not in the body: interpolating it would mint a
                // distinct log body per failure text and leave nothing for LogQL to group on. It is
                // truncated on the way there — an unbounded key-value becomes unbounded structured
                // metadata, which Loki answers by rejecting the whole entry.
                log.atWarn()
                        .addKeyValue(KEY_SERVER, connection.id())
                        .addKeyValue(KEY_DETAIL, FailureDetail.of(unreachable))
                        .log(UNREACHABLE_SERVER_MESSAGE);
            }
        }
        return List.copyOf(aggregatedTools);
    }

    @Override
    public List<ToolDescriptor> listTools(String serverId) {
        return connection(serverId).listTools();
    }

    @Override
    public ToolResult callTool(ToolInvocation invocation) {
        return connection(invocation.serverId()).callTool(invocation.name(), invocation.arguments());
    }

    private McpServerConnection connection(String serverId) {
        McpServerConnection connection = connections.get(serverId);
        if (connection == null) {
            throw new UnknownMcpServerException(serverId, connections.keySet());
        }
        return connection;
    }

    private Map<String, McpServerConnection> index(List<McpServerConnection> configured) {
        Map<String, McpServerConnection> index = new LinkedHashMap<>();
        for (McpServerConnection connection : configured) {
            if (index.putIfAbsent(connection.id(), connection) != null) {
                // Two servers under one id makes a tool call ambiguous, and the caller has no way
                // to tell which it reached. Fail at startup rather than at call time.
                throw new IllegalStateException("Duplicate MCP server id: " + connection.id());
            }
        }
        return index;
    }
}
