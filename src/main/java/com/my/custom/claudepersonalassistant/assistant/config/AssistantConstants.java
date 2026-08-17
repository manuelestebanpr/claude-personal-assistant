package com.my.custom.claudepersonalassistant.assistant.config;

/**
 * Shared constants of the assistant module.
 */
public final class AssistantConstants {

    /**
     * The single cross-chat system prompt. Per-chat history is the only other model context.
     */
    public static final String SYSTEM_PROMPT =
            "You are a personal assistant. Base every answer on a source you can check. When no "
                    + "such source is available, say so plainly instead of guessing, and do not "
                    + "review, schedule or reply to anything without one.";

    private AssistantConstants() {
    }
}
