package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.Map;

/**
 * A tool as advertised by the server.
 *
 * @param name        stable identifier used to invoke the tool
 * @param title       human-readable name for display, may be {@code null}
 * @param description what the tool does, shown to the user and to the model
 * @param inputSchema JSON Schema of the accepted arguments; a schema with no properties means the
 *                    tool takes none
 */
public record ToolDescriptor(String name, String title, String description, Map<String, Object> inputSchema) {

    /** Whether the tool can be invoked without the caller supplying any argument. */
    public boolean takesNoArguments() {
        if (inputSchema == null) {
            return true;
        }
        return !(inputSchema.get("properties") instanceof Map<?, ?> properties) || properties.isEmpty();
    }
}
