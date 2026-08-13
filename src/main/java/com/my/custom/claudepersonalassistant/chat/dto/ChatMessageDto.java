package com.my.custom.claudepersonalassistant.chat.dto;

import java.time.Instant;

/**
 * A persisted chat message as exposed to the web layer.
 */
public record ChatMessageDto(Long id, MessageRole role, String content, Instant createdAt) {
}
