package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * The refresh grant is the whole of the OAuth code, so these are the tests that stand in for a
 * security library: it must cache, it must renew before the token actually dies, and a revoked
 * grant must reach the model as a readable reason.
 */
class GoogleAccessTokensTest {

    private static final String TOKEN_URI = "https://oauth2.example/token";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-13T10:00:00Z"));

    private MockRestServiceServer server;
    private GoogleAccessTokens tokens;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = new GoogleAccessTokens(builder.build(), properties(), clock);
    }

    @Test
    void exchangesTheRefreshTokenAndCachesTheResult() {
        expectRefresh("first-token", 3600);

        assertThat(tokens.current()).isEqualTo("first-token");
        // Second call must not touch the network; verify() fails on an unexpected request.
        assertThat(tokens.current()).isEqualTo("first-token");
        server.verify();
    }

    /**
     * The margin is the point: a token renewed only once it has already expired comes back as a 401
     * the model cannot act on.
     */
    @Test
    void renewsBeforeTheTokenActuallyExpires() {
        // Both expectations up front: MockRestServiceServer refuses to take more once a request
        // has been made.
        expectRefresh("first-token", 3600);
        expectRefresh("second-token", 3600);

        assertThat(tokens.current()).isEqualTo("first-token");
        // 30 seconds short of expiry — inside the margin, so this must renew rather than reuse.
        clock.advance(Duration.ofSeconds(3600).minusSeconds(30));

        assertThat(tokens.current()).isEqualTo("second-token");
        server.verify();
    }

    @Test
    void keepsUsingATokenThatIsStillComfortablyValid() {
        expectRefresh("first-token", 3600);
        assertThat(tokens.current()).isEqualTo("first-token");

        clock.advance(Duration.ofMinutes(30));

        assertThat(tokens.current()).isEqualTo("first-token");
        server.verify();
    }

    @Test
    void reportsARevokedGrantAsSomethingTheModelCanRelay() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> tokens.current())
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void rejectsATokenResponseWithNoToken() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tokens.current())
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("no access token");
    }

    private void expectRefresh(String token, int expiresIn) {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andExpect(content().string(containsString("refresh_token=the-refresh-token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"%s\",\"expires_in\":%d,\"token_type\":\"Bearer\"}"
                                .formatted(token, expiresIn),
                        MediaType.APPLICATION_JSON));
    }

    private GoogleWorkspaceProperties properties() {
        return new GoogleWorkspaceProperties(true, "client-id", "client-secret", "the-refresh-token",
                new GoogleWorkspaceProperties.Endpoints(TOKEN_URI, "https://gmail.example",
                        "https://calendar.example", "primary"),
                new GoogleWorkspaceProperties.Limits(5, 20, 4000));
    }

    /** A clock the test moves by hand, so expiry is asserted rather than waited for. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
