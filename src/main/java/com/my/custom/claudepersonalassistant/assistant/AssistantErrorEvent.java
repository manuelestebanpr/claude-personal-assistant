package com.my.custom.claudepersonalassistant.assistant;

/**
 * Published whenever an assistant stream fails, after classification. Consumed by the audit module.
 */
public record AssistantErrorEvent(
        Long conversationId,
        ErrorClassification classification,
        Integer statusCode,
        String errorType,
        String message) {
}
