package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * The slice of the Gmail REST API the tools need.
 *
 * <p>Deliberately narrow: there is no send path here at all. Drafting is the whole write surface,
 * which is the same line Anthropic's own Google Workspace connector draws — the model can compose,
 * a human presses send.
 */
public class GmailClient {

    private static final String USER = "/users/me";

    private final RestClient restClient;
    private final GoogleAccessTokens tokens;

    GmailClient(RestClient gmailRestClient, GoogleAccessTokens tokens) {
        this.restClient = gmailRestClient;
        this.tokens = tokens;
    }

    /**
     * Runs a Gmail query and hydrates each hit.
     *
     * <p>Two round trips per result is the API's shape, not a choice: {@code messages.list} returns
     * bare ids. {@code format=metadata} keeps the follow-ups small by asking for the three headers
     * the tools actually render instead of whole messages.
     */
    public List<GmailMessage> search(String query, int maxResults) {
        MessageList found = GoogleApiCalls.call("Gmail search", () -> restClient.get()
                .uri(builder -> builder.path(USER + "/messages")
                        .queryParam("q", query)
                        .queryParam("maxResults", maxResults)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(MessageList.class));
        if (found == null || found.messages() == null) {
            return List.of();
        }
        return found.messages().stream()
                .map(reference -> metadata(reference.id()))
                .toList();
    }

    /** One message with its headers only — enough to render a search hit. */
    public GmailMessage metadata(String messageId) {
        return get(messageId, "metadata");
    }

    /** One message with its full MIME tree, so the body can be read out. */
    public GmailMessage full(String messageId) {
        return get(messageId, "full");
    }

    /**
     * Creates a draft. Never sends: {@code drafts.create} has no send side effect, and
     * {@code messages.send} is not called anywhere in this class.
     *
     * @param threadId optional; set it to hang the draft off an existing conversation
     * @return the new draft's id
     */
    public String createDraft(String to, String subject, String body, String threadId) {
        String raw = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rfc822(to, subject, body).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("raw", raw);
        if (StringUtils.hasText(threadId)) {
            message.put("threadId", threadId);
        }
        Draft draft = GoogleApiCalls.call("Gmail draft creation", () -> restClient.post()
                .uri(USER + "/drafts")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message))
                .retrieve()
                .body(Draft.class));
        if (draft == null || draft.id() == null) {
            throw new ToolExecutionException("Gmail accepted the draft but returned no id.");
        }
        return draft.id();
    }

    private GmailMessage get(String messageId, String format) {
        GmailMessage message = GoogleApiCalls.call("Gmail message fetch", () -> restClient.get()
                .uri(builder -> builder.path(USER + "/messages/{id}")
                        .queryParam("format", format)
                        .queryParam("metadataHeaders", "From", "To", "Subject", "Date")
                        .build(messageId))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(GmailMessage.class));
        if (message == null) {
            throw new ToolExecutionException("Gmail returned no message for id '%s'.".formatted(messageId));
        }
        return message;
    }

    /**
     * Assembles the RFC 5322 message Gmail wants base64url-encoded.
     *
     * <p>Headers are stripped of CR and LF before interpolation: a newline inside a caller-supplied
     * subject or recipient would end the header block early and let the rest be read as additional
     * headers, which is header injection — and here the caller is a language model relaying text a
     * third party may have written into an email it just read.
     */
    private String rfc822(String to, String subject, String body) {
        return """
                To: %s\r
                Subject: %s\r
                MIME-Version: 1.0\r
                Content-Type: text/plain; charset="UTF-8"\r
                \r
                %s""".formatted(headerSafe(to), headerSafe(subject), body);
    }

    private String headerSafe(String value) {
        return value.replaceAll("[\\r\\n]", " ").trim();
    }

    private String bearer() {
        return "Bearer " + tokens.current();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageList(List<Reference> messages) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Reference(String id, String threadId) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Draft(String id) {
    }
}
