package com.my.custom.claudepersonalassistant.assistant.dto;

import java.util.List;

/**
 * Request to stream an assistant answer: prior history (windowed by the caller), the new user
 * text, and the tools the model may call this turn (the caller's current catalogue, so a tool
 * source that comes and goes does not need a separate refresh channel).
 */
public record AssistantRequest(Long conversationId, List<HistoryMessage> history, String userText,
        List<ToolSpecification> tools) {
}
