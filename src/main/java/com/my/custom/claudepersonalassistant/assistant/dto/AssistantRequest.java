package com.my.custom.claudepersonalassistant.assistant.dto;

import java.util.List;

/**
 * Request to stream an assistant answer: prior history (windowed by the caller), the new user
 * text, the images sent with it, and the tools the model may call this turn (the caller's current
 * catalogue, so a tool source that comes and goes does not need a separate refresh channel).
 *
 * @param images attached to <em>this</em> turn only. Replayed history is text, however many images
 *               it contained — resending every photograph on every turn would multiply the cost of
 *               a long conversation by the size of its album, and the full history is replayed by
 *               default. What survives instead is the caller's note of each image's id in the
 *               history text, which is enough for the model to ask a tool to read one again
 */
public record AssistantRequest(Long conversationId, List<HistoryMessage> history, String userText,
        List<ImagePayload> images, List<ToolSpecification> tools) {

    /** A turn with nothing attached, which is most of them. */
    public AssistantRequest(Long conversationId, List<HistoryMessage> history, String userText,
            List<ToolSpecification> tools) {
        this(conversationId, history, userText, List.of(), tools);
    }
}
