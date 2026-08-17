package com.my.custom.claudepersonalassistant.mcp.event;

/**
 * Published after a tool ran to completion, whether or not it reported a business failure.
 *
 * <p>{@code serverId} is not decoration. A tool name is unique <em>per server</em>, never globally —
 * that is the whole reason {@code MultiServerMcpToolGateway} carries a server id on every
 * {@code ToolDescriptor} and {@code ToolInvocation} — so a bare tool name in an audit line cannot be
 * joined back to the server that ran it, and two servers exposing {@code search} produce one
 * indistinguishable log stream. It is the same string the client side uses in
 * {@code McpServerConnection}'s {@code server} key and in the composer's {@code !<server>/<tool>}
 * syntax, so the log line joins to those.
 *
 * @param serverId id of the MCP server that executed the tool
 * @param toolName name of the tool, unique only within {@code serverId}
 * @param failed   whether the tool ran and reported a failure, as opposed to not running at all
 */
public record ToolInvokedEvent(String serverId, String toolName, boolean failed) {

    /**
     * For a publisher that knows the tool ran here but not under which configured id — the server
     * side of MCP is never told what its clients call it. {@code ToolEventPublisher} resolves the id
     * and fills it in, so an event built this way is attributed before it is published.
     */
    public ToolInvokedEvent(String toolName, boolean failed) {
        this(null, toolName, failed);
    }
}
