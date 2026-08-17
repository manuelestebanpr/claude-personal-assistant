package com.my.custom.claudepersonalassistant.chat.persistence;

/**
 * An attachment without its bytes — what a history read needs and all it needs.
 */
public record AttachmentMetadata(Long id, Long messageId, String mediaType) {
}
