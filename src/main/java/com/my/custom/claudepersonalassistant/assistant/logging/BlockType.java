package com.my.custom.claudepersonalassistant.assistant.logging;

import java.util.Map;

/**
 * Anthropic content-block type of a streamed chunk, discriminated by the metadata keys
 * Spring AI's {@code AnthropicChatModel} sets on each {@code AssistantMessage}.
 */
public enum BlockType {

    TEXT,
    THINKING,
    SIGNATURE,
    REDACTED;

    static final String METADATA_KEY_THINKING = "thinking";
    static final String METADATA_KEY_SIGNATURE = "signature";
    static final String METADATA_KEY_REDACTED_DATA = "data";

    public static BlockType fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return TEXT;
        }
        if (metadata.containsKey(METADATA_KEY_SIGNATURE)) {
            return SIGNATURE;
        }
        if (metadata.containsKey(METADATA_KEY_THINKING)) {
            return THINKING;
        }
        if (metadata.containsKey(METADATA_KEY_REDACTED_DATA)) {
            return REDACTED;
        }
        return TEXT;
    }
}
