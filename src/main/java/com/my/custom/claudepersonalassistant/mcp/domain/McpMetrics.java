package com.my.custom.claudepersonalassistant.mcp.domain;

/**
 * Meter names and tag keys emitted by the MCP module. The module owns its own constants rather
 * than sharing a class with {@code audit}, which it is not allowed to depend on.
 */
public final class McpMetrics {

    public static final String TOOL_INVOCATIONS = "mcp.tool.invocations";
    public static final String TOOL_DURATION = "mcp.tool";

    public static final String TAG_TOOL = "tool";
    public static final String TAG_OUTCOME = "outcome";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private McpMetrics() {
    }
}
