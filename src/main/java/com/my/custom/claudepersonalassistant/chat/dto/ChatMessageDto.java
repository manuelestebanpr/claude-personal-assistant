package com.my.custom.claudepersonalassistant.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * A persisted chat message as exposed to the web layer.
 *
 * @param attachments images sent with the message, in the order they were sent, carrying ids and
 *                    types but never bytes — see {@link AttachmentDto}. Empty for the overwhelming
 *                    majority of messages, never {@code null}.
 */
public record ChatMessageDto(Long id, MessageRole role, String content, Instant createdAt,
        List<AttachmentDto> attachments) {

    /** A message with nothing attached — every assistant answer, and most user messages. */
    public ChatMessageDto(Long id, MessageRole role, String content, Instant createdAt) {
        this(id, role, content, createdAt, List.of());
    }
}
