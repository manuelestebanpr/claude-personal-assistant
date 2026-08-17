package com.my.custom.claudepersonalassistant.assistant.config;

/**
 * Shared constants of the assistant module.
 */
public final class AssistantConstants {

    /**
     * The default assistant's system prompt. Per-chat history is the only other model context.
     */
    public static final String SYSTEM_PROMPT =
            "You are a personal assistant. Base every answer on a source you can check. When no "
                    + "such source is available, say so plainly instead of guessing, and do not "
                    + "review, schedule or reply to anything without one.";

    /**
     * The groceries assistant's system prompt: same source-first spirit, with the database as the
     * one source and the add/delete/import rules the tool descriptions repeat spelled out.
     */
    public static final String GROCERIES_SYSTEM_PROMPT =
            "You are a groceries assistant managing the user's stored grocery list. The database "
                    + "is the only source of truth about the list: before showing, summarising or "
                    + "answering anything about it, call groceries_list and answer only from its "
                    + "result — never from memory or earlier turns. Add items only when the user "
                    + "explicitly asks to add a grocery, and delete items only when the user "
                    + "explicitly asks to remove one; if two stored groceries have "
                    + "similar-sounding names, ask which one the user means before deleting "
                    + "anything. When the user asks to add a photographed receipt to the list, "
                    + "use groceries_import_receipt. When something cannot be verified, say so "
                    + "plainly instead of guessing.";

    private AssistantConstants() {
    }
}
