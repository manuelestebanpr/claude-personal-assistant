package com.my.custom.claudepersonalassistant.chat;

import java.util.List;

/**
 * Module API of the chat module: conversation lifecycle plus the streaming turn.
 */
public interface ChatFacade {

    List<ConversationDto> listConversations();

    ConversationDto createConversation();

    ConversationView openConversation(Long chatId);

    void deleteConversation(Long chatId);

    /**
     * Validates the chat exists and persists the new user message — synchronously, so a
     * missing chat fails before the response body starts — then returns a {@link ChatTurn}
     * that streams the assistant answer, and persists it (partial on error/cancel, full on
     * completion), once driven.
     */
    ChatTurn prepareTurn(Long chatId, String userText);
}
