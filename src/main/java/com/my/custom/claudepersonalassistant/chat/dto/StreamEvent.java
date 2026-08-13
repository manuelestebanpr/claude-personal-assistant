package com.my.custom.claudepersonalassistant.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;

/**
 * One NDJSON line of the streaming protocol. NDJSON (rather than raw text) keeps mid-stream
 * errors distinguishable from model output once the HTTP status is already committed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(Type type, String content, ErrorClassification classification, String message) {

    public enum Type {
        DELTA,
        ERROR,
        DONE
    }

    public static StreamEvent delta(String content) {
        return new StreamEvent(Type.DELTA, content, null, null);
    }

    public static StreamEvent error(ErrorClassification classification, String message) {
        return new StreamEvent(Type.ERROR, null, classification, message);
    }

    public static StreamEvent done() {
        return new StreamEvent(Type.DONE, null, null, null);
    }
}
