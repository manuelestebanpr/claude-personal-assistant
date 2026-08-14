package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * Exchanges the stored refresh token for an access token, and caches it until it is nearly spent.
 *
 * <p>This is the whole of the OAuth code: the refresh-token grant is one form POST, so the
 * alternative — adding {@code spring-security-oauth2-client} for a single-user application with one
 * long-lived grant and no login flow — would buy an authorization-code dance nothing here performs.
 */
class GoogleAccessTokens {

    /**
     * Refresh this far before the stated expiry. A token that expires mid-flight comes back as a
     * 401 the model cannot act on, and Google's clock is not ours.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

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
    String current() {
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
        TokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.endpoints().tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        }
        catch (RestClientException rejected) {
            // Reported as a tool failure, not a protocol error: the model sees the reason and can
            // tell the user their Google authorisation needs renewing instead of silently retrying.
            throw new ToolExecutionException(
                    "Google refused to refresh the access token. The refresh token is likely expired "
                            + "or revoked — re-run the one-time authorisation. (%s)".formatted(rejected.getMessage()),
                    rejected);
        }
        if (response == null || response.accessToken() == null) {
            throw new ToolExecutionException("Google returned no access token for the refresh grant.");
        }
        this.accessToken = response.accessToken();
        long lifetime = response.expiresIn() != null ? response.expiresIn() : 0L;
        this.expiresAt = clock.instant().plusSeconds(lifetime);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }
}
