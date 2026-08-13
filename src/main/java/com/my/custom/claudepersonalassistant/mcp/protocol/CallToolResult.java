package com.my.custom.claudepersonalassistant.mcp.protocol;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code tools/call}.
 *
 * <p>{@code isError} marks a tool that ran and failed. It is deliberately not a JSON-RPC error:
 * the specification keeps those for malformed requests, and expects execution failures here so a
 * model can read the reason and correct itself.
 */
public record CallToolResult(String resultType, List<TextContent> content,
        @JsonProperty("isError") boolean isError) {

    public static CallToolResult of(String text, boolean isError) {
        return new CallToolResult(McpProtocol.RESULT_TYPE_COMPLETE, List.of(TextContent.of(text)), isError);
    }
}
