package com.my.custom.claudepersonalassistant.assistant;

/**
 * Terminal failure of an assistant stream, carrying the {@link ClassifiedError} so callers
 * can surface retryable vs terminal outcomes without depending on Anthropic SDK types.
 */
public class AssistantException extends RuntimeException {

    private final transient ClassifiedError error;

    public AssistantException(ClassifiedError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public ClassifiedError error() {
        return error;
    }

    public ErrorClassification classification() {
        return error.classification();
    }
}
