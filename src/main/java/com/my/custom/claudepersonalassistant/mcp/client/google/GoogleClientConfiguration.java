package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.time.Clock;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;

/**
 * Wiring of the Google Workspace clients.
 *
 * <p>The same {@link GoogleAccessTokens} bean serves two consumers: the Gmail and Calendar clients
 * below, and any {@code mcp.servers[].google-auth=true} entry (see {@link
 * com.my.custom.claudepersonalassistant.mcp.client.McpConnections}).
 *
 * <p>Gated as a whole, so the application starts with no Google credentials at all and the tools
 * simply do not appear in {@code tools/list} — which is also what keeps the test suite free of
 * them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleWorkspaceProperties.class)
@ConditionalOnGoogleWorkspace
class GoogleClientConfiguration {

    /**
     * Fails the context rather than the first tool call. A half-configured integration otherwise
     * shows up as a model apologising mid-answer, which is the worst place to learn that
     * {@code .env} is missing a line.
     */
    GoogleClientConfiguration(GoogleWorkspaceProperties properties) {
        require(properties.clientId(), "google.workspace.client-id");
        require(properties.clientSecret(), "google.workspace.client-secret");
        require(properties.refreshToken(), "google.workspace.refresh-token");
    }

    /**
     * Fresh builders throughout, for the reason given on {@link
     * com.my.custom.claudepersonalassistant.mcp.client.McpConnections}'s {@code restClient}: the web starter
     * brings only the server side, so no {@code RestClient.Builder} bean exists to inject.
     *
     * <p>Which is also why the {@link ObservationRegistry} is passed in explicitly on every one of
     * them. A builder starts at {@link ObservationRegistry#NOOP} and, with no
     * {@code spring-boot-restclient} module on the classpath, nothing auto-configures it back — so
     * an unwired client records no client observation and sends no {@code traceparent}, and the
     * token refresh, the Gmail call and the Calendar call vanish from the trace of the tool
     * invocation that made them. Do not "simplify" the parameter away.
     */
    @Bean
    RestClient googleTokenRestClient(ObservationRegistry observationRegistry) {
        return RestClient.builder().observationRegistry(observationRegistry).build();
    }

    @Bean
    RestClient gmailRestClient(GoogleWorkspaceProperties properties, ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl(properties.endpoints().gmailBaseUrl())
                .build();
    }

    @Bean
    RestClient calendarRestClient(GoogleWorkspaceProperties properties, ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl(properties.endpoints().calendarBaseUrl())
                .build();
    }

    @Bean
    GoogleAccessTokens googleAccessTokens(RestClient googleTokenRestClient,
            GoogleWorkspaceProperties properties, Clock mcpClock) {
        return new GoogleAccessTokens(googleTokenRestClient, properties, mcpClock);
    }

    @Bean
    GmailClient gmailClient(RestClient gmailRestClient, GoogleAccessTokens googleAccessTokens) {
        return new GmailClient(gmailRestClient, googleAccessTokens);
    }

    @Bean
    CalendarClient calendarClient(RestClient calendarRestClient, GoogleAccessTokens googleAccessTokens,
            GoogleWorkspaceProperties properties) {
        return new CalendarClient(calendarRestClient, googleAccessTokens, properties);
    }

    private void require(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(("google.workspace.enabled=true but %s is not set. Fill it "
                    + "in .env, or set google.workspace.enabled=false to run without the Gmail and "
                    + "Calendar tools.").formatted(property));
        }
    }
}
