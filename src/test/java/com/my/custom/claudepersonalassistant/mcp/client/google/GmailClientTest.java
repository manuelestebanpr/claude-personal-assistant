package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Pins what the Gmail adapter actually puts on the wire, and what it refuses to. */
class GmailClientTest {

    private static final String BASE = "https://gmail.example/gmail/v1";

    private MockRestServiceServer server;
    private GmailClient gmail;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        GoogleAccessTokens tokens = mock(GoogleAccessTokens.class);
        when(tokens.current()).thenReturn("access-token");
        gmail = new GmailClient(builder.build(), tokens);
    }

    /**
     * {@code messages.list} returns bare ids, so a search is a list plus one metadata fetch per hit
     * — asserted here because that fan-out is what the result ceiling exists to bound.
     */
    @Test
    void searchesThenHydratesEachHit() {
        server.expect(requestTo(containsString("/users/me/messages?")))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(requestTo(containsString("maxResults=2")))
                .andRespond(withSuccess(
                        "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/users/me/messages/m1")))
                .andExpect(requestTo(containsString("format=metadata")))
                .andRespond(withSuccess("""
                        {"id":"m1","threadId":"t1","snippet":"Numbers attached",
                         "payload":{"headers":[
                           {"name":"From","value":"Alice <alice@example.com>"},
                           {"name":"Subject","value":"Q3 numbers"}]}}
                        """, MediaType.APPLICATION_JSON));

        List<GmailMessage> found = gmail.search("is:unread", 2);

        assertThat(found).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo("m1");
            assertThat(message.header("subject")).isEqualTo("Q3 numbers");
            assertThat(message.header("From")).isEqualTo("Alice <alice@example.com>");
        });
        server.verify();
    }

    @Test
    void returnsNothingWhenGmailMatchesNothing() {
        server.expect(requestTo(containsString("/users/me/messages?")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(gmail.search("from:nobody", 5)).isEmpty();
        server.verify();
    }

    /**
     * A message with an attachment is {@code multipart/*} and its top-level body is empty, so the
     * text has to be found by walking the tree.
     */
    @Test
    void readsThePlainTextBodyOutOfANestedMimeTree() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Hello from the body".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(containsString("/users/me/messages/m1")))
                .andRespond(withSuccess("""
                        {"id":"m1","threadId":"t1","snippet":"Hello",
                         "payload":{"mimeType":"multipart/mixed","parts":[
                           {"mimeType":"multipart/alternative","parts":[
                             {"mimeType":"text/plain","body":{"data":"%s"}},
                             {"mimeType":"text/html","body":{"data":"PGI+aGk8L2I+"}}]}]}}
                        """.formatted(encoded), MediaType.APPLICATION_JSON));

        assertThat(gmail.full("m1").plainText()).isEqualTo("Hello from the body");
    }

    @Test
    void fallsBackToTheSnippetForAnHtmlOnlyMessage() {
        server.expect(requestTo(containsString("/users/me/messages/m1")))
                .andRespond(withSuccess("""
                        {"id":"m1","threadId":"t1","snippet":"Only markup here",
                         "payload":{"mimeType":"text/html","body":{"data":"PGI+aGk8L2I+"}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gmail.full("m1").plainText()).isEqualTo("Only markup here");
    }

    /**
     * The subject reaching this method may be text the model copied out of an email a stranger
     * sent. A newline in it would close the header block and let the rest be read as more headers —
     * a {@code Bcc:} the user never agreed to.
     */
    @Test
    void refusesToLetAHeaderCarryANewline() {
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(("""
                To: alice@example.com\r
                Subject: Hello  Bcc: mallory@example.com\r
                MIME-Version: 1.0\r
                Content-Type: text/plain; charset="UTF-8"\r
                \r
                Body text""").getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/users/me/drafts"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.message.raw").value(expected))
                .andRespond(withSuccess("{\"id\":\"d1\"}", MediaType.APPLICATION_JSON));

        String draftId = gmail.createDraft("alice@example.com",
                "Hello\r\nBcc: mallory@example.com", "Body text", null);

        assertThat(draftId).isEqualTo("d1");
        server.verify();
    }

    @Test
    void attachesADraftToAThreadWhenAskedTo() {
        server.expect(requestTo(BASE + "/users/me/drafts"))
                .andExpect(jsonPath("$.message.threadId").value("t1"))
                .andRespond(withSuccess("{\"id\":\"d1\"}", MediaType.APPLICATION_JSON));

        gmail.createDraft("alice@example.com", "Re: hi", "Body", "t1");

        server.verify();
    }

    /**
     * Google's own wording is the useful part — "insufficient authentication scopes" tells the
     * model to ask for less, where a bare 403 tells it nothing.
     */
    @Test
    void surfacesGooglesReasonSoTheModelCanActOnIt() {
        server.expect(requestTo(containsString("/users/me/messages?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body("{\"error\":{\"message\":\"Request had insufficient authentication scopes.\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gmail.search("is:unread", 5))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("insufficient authentication scopes");
    }
}
