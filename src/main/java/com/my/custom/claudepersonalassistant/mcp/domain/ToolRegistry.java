package com.my.custom.claudepersonalassistant.mcp.domain;

import java.util.List;
import java.util.Map;

import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

/**
 * The catalogue of tools this server exposes, and the single place they are invoked.
 */
public interface ToolRegistry {

    /**
     * Every registered tool. The MCP specification asks for a deterministic order so clients can
     * cache the list and model prompt caches keep hitting.
     */
    List<McpTool> tools();

    /**
     * Runs a tool by name.
     *
     * @throws UnknownToolException when no tool carries that name
     */
    ToolResult invoke(String toolName, Map<String, Object> arguments);
}
