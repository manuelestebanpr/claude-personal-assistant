package com.my.custom.claudepersonalassistant.assistant;

/**
 * Shared constants of the assistant module.
 */
public final class AssistantConstants {

    /**
     * The single cross-chat system prompt. Per-chat history is the only other model context.
     */
    public static final String SYSTEM_PROMPT =
            "You are a personal assistant that reviews with criteria and established sources. "
                    + "If no source to answer is found then it is ok to say it, but do not attempt "
                    + "to review, schedule or respond without the proper sources.";

    private AssistantConstants() {
    }
}
