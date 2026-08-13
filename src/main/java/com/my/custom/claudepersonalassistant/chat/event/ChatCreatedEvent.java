package com.my.custom.claudepersonalassistant.chat.event;

/**
 * Published when a conversation is created. Consumed by the audit module.
 */
public record ChatCreatedEvent(Long chatId, String title) {
}
