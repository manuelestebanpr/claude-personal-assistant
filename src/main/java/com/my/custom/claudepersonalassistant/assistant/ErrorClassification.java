package com.my.custom.claudepersonalassistant.assistant;

/**
 * How a streaming failure should be treated by callers and surfaced to the user.
 */
public enum ErrorClassification {
    /** Transient (rate limit, overload, network): retrying the same request may succeed. */
    RETRYABLE,
    /** Permanent (bad request, auth, not found): retrying the same request will fail again. */
    TERMINAL,
    /** Anything we could not attribute to a known Anthropic SDK failure. */
    UNKNOWN
}
