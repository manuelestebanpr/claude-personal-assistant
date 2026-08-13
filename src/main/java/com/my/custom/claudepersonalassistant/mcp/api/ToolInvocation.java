package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.Map;

/**
 * A request to run a tool with the given arguments.
 */
public record ToolInvocation(String name, Map<String, Object> arguments) {

    public ToolInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** Invocation of a tool that takes no arguments. */
    public static ToolInvocation of(String name) {
        return new ToolInvocation(name, Map.of());
    }
}
