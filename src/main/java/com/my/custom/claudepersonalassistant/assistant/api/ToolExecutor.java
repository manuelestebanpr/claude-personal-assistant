package com.my.custom.claudepersonalassistant.assistant.api;

import java.util.Map;

import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;

/**
 * Outbound port: runs a tool the model decided to call mid-turn.
 *
 * <p>The assistant module defines this rather than depending on {@code mcp} directly, so it
 * stays at zero named dependencies; the {@code chat} module supplies the real adapter over
 * {@code McpToolGateway}.
 */
@FunctionalInterface
public interface ToolExecutor {

    ToolExecutionResult execute(String toolName, Map<String, Object> arguments);
}
