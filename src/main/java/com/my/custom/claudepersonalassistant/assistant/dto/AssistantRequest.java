package com.my.custom.claudepersonalassistant.assistant.dto;

import java.util.List;

/**
 * Request to stream an assistant answer: prior history (windowed by the caller) plus the new user text.
 */
public record AssistantRequest(Long conversationId, List<HistoryMessage> history, String userText) {
}
