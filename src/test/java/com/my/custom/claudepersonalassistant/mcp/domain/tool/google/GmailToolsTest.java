package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.my.custom.claudepersonalassistant.mcp.client.google.GmailClient;
import com.my.custom.claudepersonalassistant.mcp.client.google.GmailMessage;
import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the model is handed, and what it is stopped from doing. */
class GmailToolsTest {

    private final GmailClient gmail = mock(GmailClient.class);
    private final GoogleWorkspaceProperties properties = new GoogleWorkspaceProperties(
            true, "id", "secret", "refresh",
            new GoogleWorkspaceProperties.Endpoints("t", "g", "c", "primary"),
            new GoogleWorkspaceProperties.Limits(2, 3, 20));

    private final GmailSearchMessagesTool search = new GmailSearchMessagesTool(gmail, properties);
    private final GmailGetMessageTool read = new GmailGetMessageTool(gmail, properties);
    private final GmailCreateDraftTool draft = new GmailCreateDraftTool(gmail);

    @Test
    void searchRendersTheFieldsNeededToPickAMessageToOpen() {
        when(gmail.search(eq("is:unread"), any(Integer.class)))
                .thenReturn(List.of(message("m1", "t1", "Alice <a@example.com>", "Q3 numbers", "Attached.")));

        String rendered = search.execute(Map.of("query", "is:unread"));

        assertThat(rendered)
                .contains("1 message(s)")
                .contains("id: m1")
                .contains("Alice <a@example.com>")
                .contains("Q3 numbers")
                .contains("Attached.");
    }

    /**
     * Clamped rather than rejected: a model that asks for 500 means "as many as you can", and an
     * error would just cost a turn to recover from.
     */
    @Test
    void searchClampsAnOverlargeRequestToTheCeiling() {
        when(gmail.search(any(), any(Integer.class))).thenReturn(List.of());

        search.execute(Map.of("query", "is:unread", "max_results", 500));

        verify(gmail).search("is:unread", 3);
    }

    @Test
    void searchFallsBackToTheConfiguredDefault() {
        when(gmail.search(any(), any(Integer.class))).thenReturn(List.of());

        search.execute(Map.of("query", "is:unread"));

        verify(gmail).search("is:unread", 2);
    }

    @Test
    void searchSaysSoWhenNothingMatched() {
        when(gmail.search(any(), any(Integer.class))).thenReturn(List.of());

        assertThat(search.execute(Map.of("query", "from:nobody"))).contains("No messages match");
    }

    @Test
    void aMissingArgumentBecomesSomethingTheModelCanFix() {
        assertThatThrownBy(() -> search.execute(Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("query");
    }

    /** A tool result is context window; a long quoted thread would spend more of it than the chat. */
    @Test
    void readTruncatesALongBodyAndSaysThatItDid() {
        when(gmail.full("m1")).thenReturn(message("m1", "t1", "Alice", "Long one",
                "0123456789012345678901234567890123456789"));

        String rendered = read.execute(Map.of("message_id", "m1"));

        assertThat(rendered).contains("[truncated at 20 characters]");
        assertThat(rendered).doesNotContain("0123456789012345678901234567890123456789");
    }

    @Test
    void draftReportsThatNothingWasSent() {
        when(gmail.createDraft("alice@example.com", "Hi", "Body", null)).thenReturn("d1");

        String rendered = draft.execute(Map.of(
                "to", "alice@example.com", "subject", "Hi", "body", "Body"));

        assertThat(rendered).contains("d1").contains("NOT been sent");
    }

    /** Names are the model's addressing scheme; renaming one silently breaks every saved prompt. */
    @Test
    void toolsKeepTheirPublishedNamesAndClosedSchemas() {
        assertThat(search.name()).isEqualTo("gmail_search_messages");
        assertThat(read.name()).isEqualTo("gmail_get_message");
        assertThat(draft.name()).isEqualTo("gmail_create_draft");
        assertThat(search.inputSchema()).containsEntry("additionalProperties", false);
        assertThat(search.inputSchema()).containsEntry("required", List.of("query"));
        assertThat(draft.inputSchema()).containsEntry("required", List.of("to", "subject", "body"));
    }

    private GmailMessage message(String id, String threadId, String from, String subject, String body) {
        return new GmailMessage(id, threadId, body, new GmailMessage.Payload("text/plain",
                List.of(new GmailMessage.Header("From", from), new GmailMessage.Header("Subject", subject)),
                new GmailMessage.Body(java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                null));
    }
}
