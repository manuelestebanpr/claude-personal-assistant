package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.client.google.GmailClient;
import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;

/**
 * Composes a draft. It is never sent.
 *
 * <p>Drafting is the entire write surface of this integration, and the boundary is structural
 * rather than a policy in the prompt: {@link GmailClient} has no send method, so no instruction and
 * no injected text in a mail the model just read can produce an outgoing message. It is the same
 * line Anthropic's own Google Workspace connector draws.
 */
@Component
@ConditionalOnGoogleWorkspace
class GmailCreateDraftTool implements McpTool {

    static final String NAME = "gmail_create_draft";

    private final GmailClient gmail;

    GmailCreateDraftTool(GmailClient gmailClient) {
        this.gmail = gmailClient;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Draft a Gmail message";
    }

    @Override
    public String description() {
        return "Saves a plain-text email as a draft in the user's Gmail. The draft is NOT sent — the "
                + "user reviews and sends it themselves — so say so when you report back. To draft a "
                + "reply, pass the thread id from gmail_search_messages so it lands in the right "
                + "conversation.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required("to", ToolSchema.string("Recipient email address."))
                .required("subject", ToolSchema.string("Subject line."))
                .required("body", ToolSchema.string("Plain-text body of the email."))
                .optional("thread_id", ToolSchema.string(
                        "Optional thread id to attach the draft to an existing conversation."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String to = ToolArguments.requiredText(arguments, "to");
        String subject = ToolArguments.requiredText(arguments, "subject");
        String body = ToolArguments.requiredText(arguments, "body");
        String threadId = ToolArguments.optionalText(arguments, "thread_id");

        String draftId = gmail.createDraft(to, subject, body, threadId);
        return "Draft saved to Gmail (id %s), addressed to %s with subject '%s'. It has NOT been sent — "
                .formatted(draftId, to, subject)
                + "the user must review and send it from Gmail.";
    }
}
