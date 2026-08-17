package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;

import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ImageUpload;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;

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

    /**
     * Appends a message together with the images sent alongside it, in one transaction — a message
     * whose attachments failed to store would render as text the user never typed.
     *
     * @return the message, with its attachments carrying the ids the database assigned
     */
    ChatMessageDto append(Long chatId, MessageRole role, String content, List<ImageUpload> images);

    /** Deletes every message belonging to a conversation, and everything attached to them. */
    void deleteAll(Long chatId);
}
