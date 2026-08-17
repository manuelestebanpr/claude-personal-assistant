package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * A stored attachment as the web layer and the model see it: an id and a type, never the bytes.
 *
 * <p>Keeping the bytes out is what lets a conversation be rendered without loading every photograph
 * in it — the page emits {@code /attachments/{id}} and the browser fetches each one only if it is
 * actually scrolled into view.
 *
 * <p>The id is also what the model is told, so it can hand it to a tool that needs to read the
 * image again.
 */
public record AttachmentDto(Long id, String mediaType) {
}
