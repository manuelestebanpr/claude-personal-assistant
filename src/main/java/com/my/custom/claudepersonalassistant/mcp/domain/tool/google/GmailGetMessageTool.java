package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.client.google.GmailClient;
import com.my.custom.claudepersonalassistant.mcp.client.google.GmailMessage;
import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Reads one message in full.
 *
 * <p>The body is truncated rather than paginated: a tool result goes straight into the model's
 * context window, and a mail thread with a long quoted history would otherwise cost more of it than
 * the conversation it is supposed to inform.
 */
@Component
@ConditionalOnGoogleWorkspace
class GmailGetMessageTool implements McpTool {

    static final String NAME = "gmail_get_message";

    private final GmailClient gmail;
    private final GoogleWorkspaceProperties.Limits limits;

    GmailGetMessageTool(GmailClient gmailClient, GoogleWorkspaceProperties properties) {
        this.gmail = gmailClient;
        this.limits = properties.limits();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Read a Gmail message";
    }

    @Override
    public String description() {
        return "Reads one Gmail message in full, returning its sender, recipients, subject, date and "
                + "plain-text body. Takes a message id from gmail_search_messages. Long bodies are "
                + "truncated.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required("message_id",
                        ToolSchema.string("Message id, as returned by gmail_search_messages."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String messageId = ToolArguments.requiredText(arguments, "message_id");
        GmailMessage message = gmail.full(messageId);

        String body = message.plainText();
        boolean truncated = body.length() > limits.maxBodyCharacters();
        if (truncated) {
            body = body.substring(0, limits.maxBodyCharacters());
        }

        return """
                From: %s
                To: %s
                Date: %s
                Subject: %s
                Thread: %s

                %s%s""".formatted(
                message.header("From"),
                message.header("To"),
                message.header("Date"),
                message.header("Subject"),
                message.threadId(),
                body.strip(),
                truncated ? "%n%n[truncated at %d characters]".formatted(limits.maxBodyCharacters()) : "");
    }
}
