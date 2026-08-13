package com.my.custom.claudepersonalassistant.assistant.event;

import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;

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
