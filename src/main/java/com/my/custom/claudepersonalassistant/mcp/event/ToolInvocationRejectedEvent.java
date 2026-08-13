package com.my.custom.claudepersonalassistant.mcp.event;

/**
 * Published when a caller asked for a tool this server does not expose. Worth auditing on its own:
 * it means a client is working from a stale tool list.
 */
public record ToolInvocationRejectedEvent(String toolName) {
}
