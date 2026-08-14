package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.List;

/**
 * Outbound port to the MCP servers this application connects to.
 *
 * <p>Plural on purpose. One of the connections happens to be this application's own {@code /mcp}
 * endpoint over loopback — so the protocol is genuinely exercised rather than short-circuited in
 * process — but nothing here treats it as special, and adding a third-party server is a property
 * rather than a code change.
 */
public interface McpToolGateway {

    /**
     * Every configured server, reachable or not, each reporting what it just answered.
     *
     * <p>Never throws: a server that is down is a row saying so, not a failed page.
     */
    List<McpServerDescriptor> listServers();

    /**
     * Every tool across every reachable server.
     *
     * <p>Names are unique per server, not globally, so a name offered by two servers is kept from
     * the first and dropped from the rest — the model addresses tools by bare name and could not
     * express the difference.
     */
    List<ToolDescriptor> listTools();

    /**
     * Tools offered by one server, in the order it returned them.
     *
     * @throws McpClientException when that server cannot be reached or answers with a protocol error
     */
    List<ToolDescriptor> listTools(String serverId);

    /**
     * Invokes a tool. A tool that runs but fails answers with {@link ToolResult#error()} set —
     * only transport and protocol failures raise {@link McpClientException}.
     */
    ToolResult callTool(ToolInvocation invocation);
}
