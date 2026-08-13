package com.my.custom.claudepersonalassistant.assistant.dto;

/**
 * A single prior message of a conversation, replayed as model context.
 */
public record HistoryMessage(HistoryRole role, String text) {
}
