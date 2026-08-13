package com.my.custom.claudepersonalassistant.mcp.domain;

import java.util.Map;

/**
 * A tool this server exposes.
 *
 * <p>The extension point of the module: adding a capability means adding one implementation and
 * nothing else — the registry, the endpoint and the metrics all work off this interface.
 */
public interface McpTool {

    /**
     * Stable identifier. The MCP specification asks for letters, digits, underscore, hyphen and
     * dot only, and treats the name as case-sensitive.
     */
    String name();

    /** Human-readable name for display. */
    String title();

    /** What the tool does. Shown in the UI and given to the model. */
    String description();

    /**
     * JSON Schema of the accepted arguments. Tools without parameters should return the
     * specification's recommended empty form, {@code {"type":"object","additionalProperties":false}}.
     */
    Map<String, Object> inputSchema();

    /**
     * Runs the tool.
     *
     * @return the textual result
     * @throws ToolExecutionException when the tool ran and failed in a way the caller (or the
     *                                model) can act on
     */
    String execute(Map<String, Object> arguments);
}
