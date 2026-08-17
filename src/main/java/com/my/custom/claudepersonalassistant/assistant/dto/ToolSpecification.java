package com.my.custom.claudepersonalassistant.assistant.dto;

import java.util.Map;

/**
 * A tool the model may call during a turn: where it came from, its name, what it does, and the
 * JSON Schema of the arguments it accepts.
 *
 * @param serverId the MCP server offering the tool, or {@code null} when the origin is unknown —
 *                 carried so an assistant profile can allowlist whole servers, not just names
 */
public record ToolSpecification(String serverId, String name, String description,
        Map<String, Object> inputSchema) {

    /** A tool of unknown origin — fails any server allowlist, passes profiles without one. */
    public ToolSpecification(String name, String description, Map<String, Object> inputSchema) {
        this(null, name, description, inputSchema);
    }
}
