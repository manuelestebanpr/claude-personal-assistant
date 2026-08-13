package com.my.custom.claudepersonalassistant.mcp.api;

/**
 * The MCP server could not be reached, or answered with a protocol-level error.
 *
 * <p>Distinct from a tool that ran and failed, which comes back as a {@link ToolResult} carrying
 * {@link ToolResult#error()}.
 */
public class McpClientException extends RuntimeException {

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
