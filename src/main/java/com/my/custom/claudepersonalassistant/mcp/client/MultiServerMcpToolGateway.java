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

/**
 * Fans the gateway out across every configured server.
 *
 * <p>Connection order is configuration order, so the picker reads the way the file does.
 */
@Component
class MultiServerMcpToolGateway implements McpToolGateway {

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
        List<ToolDescriptor> aggregated = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (McpServerConnection connection : connections.values()) {
            try {
                for (ToolDescriptor tool : connection.listTools()) {
                    if (claimed.add(tool.name())) {
                        aggregated.add(tool);
                    }
                    else {
                        // The model addresses a tool by bare name, so it could not express which
                        // server it meant. First server configured wins.
                        log.atWarn()
                                .addKeyValue("tool", tool.name())
                                .addKeyValue("server", connection.id())
                                .log("Duplicate tool name; keeping the first server's and dropping this one");
                    }
                }
            }
            catch (McpClientException unreachable) {
                log.atWarn()
                        .addKeyValue("server", connection.id())
                        .log("Tool catalogue unavailable: {}", unreachable.getMessage());
            }
        }
        return List.copyOf(aggregated);
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
