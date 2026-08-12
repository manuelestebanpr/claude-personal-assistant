package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;

import com.my.custom.claudepersonalassistant.chat.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.MessageRole;

/**
 * Message persistence: the JPA store is the single source of truth for chat memory. Not
 * referenced outside {@code chat.service}, so it stays package-private.
 */
interface MessageService {

    /** Full history of a conversation, oldest first. */
    List<ChatMessageDto> history(Long chatId);

    /**
     * The trailing slice of history used as model context, honoring
     * {@code chat.context-window-size} ({@code 0} = full history), oldest first.
     */
    List<ChatMessageDto> contextWindow(Long chatId);

    ChatMessageDto append(Long chatId, MessageRole role, String content);

    /** Deletes every message belonging to a conversation. */
    void deleteAll(Long chatId);
}
