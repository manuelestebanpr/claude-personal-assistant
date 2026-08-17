package com.my.custom.claudepersonalassistant.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Credentials and tunables of the Gmail and Calendar tools.
 *
 * <p>Single user, so there is no token store and no per-user consent: one refresh token obtained
 * once out of band lives in {@code .env} beside the Anthropic key, and every call exchanges it for
 * a short-lived access token via {@link
 * com.my.custom.claudepersonalassistant.mcp.client.google.GoogleAccessTokens}.
 *
 * <p>The same credentials also authenticate an {@code mcp.servers[].google-auth=true} entry, which
 * is how Google's own remote Gmail and Calendar MCP servers would be reached. Those servers are
 * gated behind the Google Workspace Developer Preview Program and reject a consumer account with
 * HTTP 403, so the local tools below are what actually runs — see the note in
 * {@code application.properties}.
 *
 * @param enabled      gates the whole tool group. Off by default so the application boots — and the
 *                     test suite runs — with no Google credentials present at all
 * @param clientId     OAuth 2.0 client id of the Google Cloud project
 * @param clientSecret its secret
 * @param refreshToken the long-lived grant, obtained once out of band
 */
@ConfigurationProperties("google.workspace")
public record GoogleWorkspaceProperties(
        @DefaultValue("false") boolean enabled,
        String clientId,
        String clientSecret,
        String refreshToken,
        @DefaultValue Endpoints endpoints,
        @DefaultValue Limits limits) {

    /**
     * Overridable so tests can point the clients at a local stub, and so a future migration to
     * Google's remote MCP servers is a property change rather than an edit.
     */
    public record Endpoints(
            @DefaultValue("https://oauth2.googleapis.com/token") String tokenUri,
            @DefaultValue("https://gmail.googleapis.com/gmail/v1") String gmailBaseUrl,
            @DefaultValue("https://www.googleapis.com/calendar/v3") String calendarBaseUrl,
            @DefaultValue("primary") String calendarId) {
    }

    /**
     * Every byte a tool returns is spent on model context, and a Gmail search can match thousands
     * of messages. These are the ceilings that keep one tool call from filling the context window.
     *
     * @param defaultResults used when the model does not ask for a count
     * @param maxResults     hard ceiling; a larger request is clamped rather than rejected, because
     *                       a clamped answer is more useful to the model than an error
     * @param maxBodyCharacters cut-off for a single message body
     */
    public record Limits(
            @DefaultValue("5") int defaultResults,
            @DefaultValue("20") int maxResults,
            @DefaultValue("4000") int maxBodyCharacters) {
    }
}
