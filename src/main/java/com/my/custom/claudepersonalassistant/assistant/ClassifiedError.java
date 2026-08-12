package com.my.custom.claudepersonalassistant.assistant;

/**
 * Result of classifying a streaming failure. Status code and Anthropic error type
 * (for example {@code rate_limit_error}, {@code overloaded_error}) are present only
 * for API-level failures.
 */
public record ClassifiedError(ErrorClassification classification, Integer statusCode, String errorType, String message) {
}
