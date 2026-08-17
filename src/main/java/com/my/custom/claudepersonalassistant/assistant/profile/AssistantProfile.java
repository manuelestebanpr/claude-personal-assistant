package com.my.custom.claudepersonalassistant.assistant.profile;

import java.util.Set;

/**
 * Everything that makes one assistant different from another: identity for the picker, the model
 * and system prompt for the call, and the slice of the tool catalogue it may offer.
 *
 * <p>Module-internal on purpose — other modules see only the
 * {@link com.my.custom.claudepersonalassistant.assistant.dto.AssistantDescriptor} projection.
 *
 * @param allowedServers MCP server ids the assistant may draw tools from; empty means any
 * @param allowedTools   tool names the assistant may offer the model; empty means any
 */
public record AssistantProfile(String id, String displayName, String description, String model,
        String systemPrompt, Set<String> allowedServers, Set<String> allowedTools) {

    public AssistantProfile {
        allowedServers = Set.copyOf(allowedServers);
        allowedTools = Set.copyOf(allowedTools);
    }

    /**
     * Whether a tool from {@code serverId} named {@code toolName} may be offered to the model.
     *
     * <p>A {@code null} server id (a tool of unknown origin) fails a non-empty server allowlist
     * rather than passing it: an allowlist that admitted what it cannot identify would not be one.
     */
    public boolean allowsTool(String serverId, String toolName) {
        return allowsServer(serverId) && (allowedTools.isEmpty() || allowedTools.contains(toolName));
    }

    private boolean allowsServer(String serverId) {
        return allowedServers.isEmpty() || (serverId != null && allowedServers.contains(serverId));
    }
}
