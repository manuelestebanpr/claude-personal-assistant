package com.my.custom.claudepersonalassistant.mcp.api;

/**
 * One MCP server this application can talk to, and how that went last time it asked.
 *
 * <p>{@code reachable} is observed, not configured: a server is listed whether or not it answers,
 * because "configured but down" is something the user needs to see rather than a row that silently
 * vanishes.
 *
 * @param id        stable identifier, used to address the server when calling one of its tools
 * @param name      human-readable label
 * @param url       endpoint the client posts to
 * @param protocol  MCP revision family the client uses for it
 * @param reachable whether the last catalogue request succeeded
 * @param toolCount tools it exposed, zero when unreachable or when it exposes none
 * @param detail    why it is unreachable, or {@code null} when it is fine
 */
public record McpServerDescriptor(String id, String name, String url, String protocol,
        boolean reachable, int toolCount, String detail) {
}
