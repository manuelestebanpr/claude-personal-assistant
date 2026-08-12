package com.my.custom.claudepersonalassistant.chat;

import java.time.Instant;

/**
 * A conversation as exposed to the web layer and other modules.
 */
public record ConversationDto(Long id, String title, Instant createdAt) {
}
