package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A tool as advertised by one of the connected servers.
 *
 * @param serverId    which server offers it — a tool name is only unique within its own server
 * @param serverName  that server's label. Carried rather than looked up: the connection already
 *                    knows it, and resolving it later would mean probing every server again
 * @param name        stable identifier used to invoke the tool
 * @param title       human-readable name for display, may be {@code null}
 * @param description what the tool does, shown to the user and to the model
 * @param inputSchema JSON Schema of the accepted arguments; a schema with no properties means the
 *                    tool takes none
 */
public record ToolDescriptor(String serverId, String serverName, String name, String title,
        String description, Map<String, Object> inputSchema) {

    private static final String DEFAULT_TYPE = "string";

    /**
     * The arguments this tool accepts, in the order the server declared them.
     *
     * <p>Order is preserved rather than sorted: the server emits its schema deliberately ordered,
     * and a form that reshuffles its fields between page loads is its own kind of bug.
     */
    public List<ToolParameter> parameters() {
        if (inputSchema == null || !(inputSchema.get("properties") instanceof Map<?, ?> properties)) {
            return List.of();
        }
        Collection<?> required = inputSchema.get("required") instanceof Collection<?> names
                ? names
                : List.of();
        return properties.entrySet().stream()
                .map(property -> toParameter(String.valueOf(property.getKey()), property.getValue(), required))
                .toList();
    }

    /** Whether the tool can be invoked without the caller supplying any argument. */
    public boolean takesNoArguments() {
        return parameters().isEmpty();
    }

    private ToolParameter toParameter(String name, Object specification, Collection<?> required) {
        Map<?, ?> fields = specification instanceof Map<?, ?> map ? map : Map.of();
        String type = text(fields.get("type"));
        return new ToolParameter(name, text(fields.get("description")),
                type.isBlank() ? DEFAULT_TYPE : type, required.contains(name));
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
