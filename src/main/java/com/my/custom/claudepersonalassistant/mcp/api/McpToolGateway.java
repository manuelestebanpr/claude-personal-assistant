package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.List;

/**
 * Outbound port to an MCP server.
 *
 * <p>The default adapter talks to this application's own {@code /mcp} endpoint over loopback, so
 * the protocol is genuinely exercised rather than short-circuited in process; pointing it at a
 * remote server is a configuration change.
 */
public interface McpToolGateway {

    /**
     * Tools the server currently offers, in the deterministic order it returned them.
     *
     * @throws McpClientException when the server cannot be reached or answers with a protocol error
     */
    List<ToolDescriptor> listTools();

    /**
     * Invokes a tool. A tool that runs but fails answers with {@link ToolResult#error()} set —
     * only transport and protocol failures raise {@link McpClientException}.
     */
    ToolResult callTool(ToolInvocation invocation);
}
