package com.my.custom.claudepersonalassistant.assistant.dto;

/**
 * An assistant as other modules see it: enough to render a picker card and address a conversation
 * to it. The model, system prompt and tool allowlist deliberately stay inside this module — a
 * caller that could read them would soon depend on them.
 */
public record AssistantDescriptor(String id, String displayName, String description) {
}
