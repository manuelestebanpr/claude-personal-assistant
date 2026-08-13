package com.my.custom.claudepersonalassistant.chat.dto;

import java.util.List;

/**
 * An opened conversation with its full, rehydrated message history.
 */
public record ConversationView(ConversationDto conversation, List<ChatMessageDto> messages) {
}
