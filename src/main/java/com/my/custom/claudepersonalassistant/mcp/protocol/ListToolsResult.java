package com.my.custom.claudepersonalassistant.mcp.protocol;

import java.util.List;

/**
 * Result of {@code tools/list}. Pagination is not used: the catalogue is small enough to return
 * whole, so no {@code nextCursor} is emitted.
 */
public record ListToolsResult(String resultType, List<ToolDefinition> tools) {

    public static ListToolsResult of(List<ToolDefinition> tools) {
        return new ListToolsResult(McpProtocol.RESULT_TYPE_COMPLETE, tools);
    }
}
