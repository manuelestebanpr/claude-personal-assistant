package com.my.custom.claudepersonalassistant.mcp.protocol;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool as it appears in a {@code tools/list} result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(String name, String title, String description, Map<String, Object> inputSchema) {
}
