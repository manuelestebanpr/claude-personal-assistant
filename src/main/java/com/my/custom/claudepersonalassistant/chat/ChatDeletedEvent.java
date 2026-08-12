package com.my.custom.claudepersonalassistant.chat;

/**
 * Published when a conversation (and its messages) is deleted. Consumed by the audit module.
 */
public record ChatDeletedEvent(Long chatId, String title) {
}
