package com.my.custom.claudepersonalassistant.mcp.event;

/**
 * Published after a tool ran to completion, whether or not it reported a business failure.
 */
public record ToolInvokedEvent(String toolName, boolean failed) {
}
