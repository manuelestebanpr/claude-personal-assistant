package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.Map;

/**
 * A request to run a tool on a named server with the given arguments.
 *
 * <p>The server is part of the request because two servers may both offer a {@code search}: without
 * it, which one runs would depend on connection order.
 */
public record ToolInvocation(String serverId, String name, Map<String, Object> arguments) {

    public ToolInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** Invocation of a tool that takes no arguments. */
    public static ToolInvocation of(String serverId, String name) {
        return new ToolInvocation(serverId, name, Map.of());
    }
}
