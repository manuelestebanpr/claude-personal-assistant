package com.my.custom.claudepersonalassistant.mcp.protocol;

/**
 * A textual content block of a tool result.
 */
public record TextContent(String type, String text) {

    public static TextContent of(String text) {
        return new TextContent("text", text);
    }
}
