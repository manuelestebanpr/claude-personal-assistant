package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.client.google.GmailClient;
import com.my.custom.claudepersonalassistant.mcp.client.google.GmailMessage;
import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;

/**
 * Finds messages in the user's mailbox.
 *
 * <p>Returns headers and a snippet, never bodies: this is the tool the model reaches for first and
 * usually several times, so it has to stay cheap. {@code gmail_get_message} is the follow-up for
 * the one hit that matters.
 */
@Component
@ConditionalOnGoogleWorkspace
class GmailSearchTool implements McpTool {

    static final String NAME = "gmail_search_messages";

    private static final int SNIPPET_LIMIT = 220;

    private final GmailClient gmail;
    private final GoogleWorkspaceProperties.Limits limits;

    GmailSearchTool(GmailClient gmailClient, GoogleWorkspaceProperties properties) {
        this.gmail = gmailClient;
        this.limits = properties.limits();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Search Gmail";
    }

    @Override
    public String description() {
        return "Searches the user's Gmail and returns matching messages with sender, subject, date "
                + "and a short snippet. Use the same query syntax as the Gmail search box, for "
                + "example 'is:unread', 'from:alice@example.com', 'subject:invoice newer_than:7d', "
                + "'has:attachment'. Returns message ids — pass one to gmail_get_message to read the "
                + "full body.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required("query", ToolSchema.string(
                        "Gmail search query, e.g. 'from:alice@example.com is:unread newer_than:7d'."))
                .optional("max_results", ToolSchema.integer(
                        "How many messages to return. Defaults to %d.".formatted(limits.defaultResults()),
                        1, limits.maxResults()))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = ToolArguments.requiredText(arguments, "query");
        int maxResults = ToolArguments.boundedNumber(arguments, "max_results",
                limits.defaultResults(), limits.maxResults());

        List<GmailMessage> messages = gmail.search(query, maxResults);
        if (messages.isEmpty()) {
            return "No messages match '%s'.".formatted(query);
        }

        StringBuilder rendered = new StringBuilder(
                "%d message(s) matching '%s':%n".formatted(messages.size(), query));
        for (GmailMessage message : messages) {
            rendered.append("%n- id: %s (thread %s)%n".formatted(message.id(), message.threadId()))
                    .append("  From: %s%n".formatted(message.header("From")))
                    .append("  Date: %s%n".formatted(message.header("Date")))
                    .append("  Subject: %s%n".formatted(message.header("Subject")))
                    .append("  %s%n".formatted(snippet(message)));
        }
        return rendered.toString();
    }

    private String snippet(GmailMessage message) {
        String snippet = message.snippet() == null ? "" : message.snippet().replace('\n', ' ').trim();
        return snippet.length() <= SNIPPET_LIMIT ? snippet : snippet.substring(0, SNIPPET_LIMIT) + "…";
    }
}
