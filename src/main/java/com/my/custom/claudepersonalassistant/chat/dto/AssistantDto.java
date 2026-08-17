package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * An assistant as the home page renders it: one picker card. The chat module's own translation of
 * the assistant module's descriptor, for the same reason {@link McpServerDto} exists — the web
 * layer never learns what the assistant module is.
 */
public record AssistantDto(String id, String name, String description) {
}
