package com.my.custom.claudepersonalassistant.mcp.api;

/**
 * The outcome of a tool call.
 *
 * @param text  the tool's textual output, or the failure description when {@code error} is set
 * @param error whether the tool ran and failed — a business failure the model can act on, as
 *              opposed to a protocol failure, which surfaces as {@link McpClientException}
 */
public record ToolResult(String text, boolean error) {

    public static ToolResult ok(String text) {
        return new ToolResult(text, false);
    }

    public static ToolResult failed(String text) {
        return new ToolResult(text, true);
    }
}
