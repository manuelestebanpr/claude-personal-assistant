package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;

import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;

/**
 * Conversation lifecycle: list, create, resolve, title derivation, delete. Not referenced
 * outside {@code chat.service}, so it stays package-private.
 */
interface ConversationService {

    List<ConversationDto> list();

    ConversationDto create();

    /**
     * @throws ChatNotFoundException when the conversation does not exist
     */
    ConversationDto get(Long chatId);

    /**
     * Derives the conversation title from the first user message (truncated to the configured
     * maximum). Only applies while the conversation still carries the default title.
     */
    void applyDerivedTitle(Long chatId, String firstUserText);

    /**
     * Deletes the conversation and all of its messages.
     *
     * @throws ChatNotFoundException when the conversation does not exist
     */
    void delete(Long chatId);
}
