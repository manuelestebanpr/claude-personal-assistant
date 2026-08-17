package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * Exchanges the stored refresh token for an access token, and caches it until it is nearly spent.
 *
 * <p>This is the whole of the OAuth code: the refresh-token grant is one form POST, so the
 * alternative — adding {@code spring-security-oauth2-client} for a single-user application with one
 * long-lived grant and no login flow — would buy an authorization-code dance nothing here performs.
 *
 * <p>Public so {@code mcp.client} and {@code mcp.config} can use it as a token supplier for the
 * Google MCP servers' per-request {@code Authorization} header — still constructed only here,
 * from {@link GoogleClientConfiguration}.
 */
public class GoogleAccessTokens {

    /**
     * The one failure the operator has to act on: the refresh token expires after seven days while
     * the consent screen is in "Testing", and without this line it reaches them only as a generic
     * "MCP server unreachable" from the picker, with no hint that re-authorising is the fix.
     */
    static final String REFRESH_REJECTED_MESSAGE = "Google rejected the refresh-token grant";

    /** A 2xx with no {@code access_token} in it — a contract break, not an expired grant. */
    static final String NO_TOKEN_MESSAGE = "Google returned no access token for the refresh grant";

    static final String KEY_HTTP_STATUS = "httpStatus";

    /** Stands in for the status of a request that never reached Google at all. */
    static final int STATUS_NO_RESPONSE = 0;

    /**
     * Refresh this far before the stated expiry. A token that expires mid-flight comes back as a
     * 401 the model cannot act on, and Google's clock is not ours.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(GoogleAccessTokens.class);

    private final RestClient restClient;
    private final GoogleWorkspaceProperties properties;
    private final Clock clock;

    /**
     * Serialises refreshes rather than the reads: several tool calls can run concurrently on
     * virtual threads, and without this a single expiry would send one refresh request per
     * in-flight call.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private String accessToken;
    private Instant expiresAt = Instant.MIN;

    GoogleAccessTokens(RestClient googleTokenRestClient, GoogleWorkspaceProperties properties, Clock clock) {
        this.restClient = googleTokenRestClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** A usable access token, refreshed if the cached one is gone or nearly spent. */
    public String current() {
        lock.lock();
        try {
            if (accessToken != null && clock.instant().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
                return accessToken;
            }
            refresh();
            return accessToken;
        }
        finally {
            lock.unlock();
        }
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("refresh_token", properties.refreshToken());
        form.add("grant_type", "refresh_token");
        // toEntity rather than body: the status is the whole diagnostic value of the log lines
        // below, and body() discards it.
        ResponseEntity<TokenResponse> response;
        try {
            response = restClient.post()
                    .uri(properties.endpoints().tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(TokenResponse.class);
        }
        catch (RestClientException rejected) {
            // Nothing from the request or the response is logged beyond the status: the form
            // carries the client secret and the refresh token, and Google echoes neither safely.
            log.atWarn()
                    .addKeyValue(KEY_HTTP_STATUS, statusOf(rejected))
                    .log(REFRESH_REJECTED_MESSAGE);
            // Reported as a tool failure, not a protocol error: the model sees the reason and can
            // tell the user their Google authorisation needs renewing instead of silently retrying.
            throw new ToolExecutionException(
                    "Google refused to refresh the access token. The refresh token is likely expired "
                            + "or revoked — re-run the one-time authorisation. (%s)".formatted(rejected.getMessage()),
                    rejected);
        }
        TokenResponse token = response.getBody();
        if (token == null || token.accessToken() == null) {
            log.atWarn()
                    .addKeyValue(KEY_HTTP_STATUS, response.getStatusCode().value())
                    .log(NO_TOKEN_MESSAGE);
            throw new ToolExecutionException("Google returned no access token for the refresh grant.");
        }
        this.accessToken = token.accessToken();
        long lifetime = token.expiresIn() != null ? token.expiresIn() : 0L;
        this.expiresAt = clock.instant().plusSeconds(lifetime);
    }

    private int statusOf(RestClientException failure) {
        return failure instanceof RestClientResponseException answered
                ? answered.getStatusCode().value()
                : STATUS_NO_RESPONSE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }
}
