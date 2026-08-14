package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.time.Clock;

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
     * Fresh builders throughout, for the reason given on {@code mcpRestClient}: the web starter
     * brings only the server side, so no {@code RestClient.Builder} bean exists to inject.
     */
    @Bean
    RestClient googleTokenRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    RestClient gmailRestClient(GoogleWorkspaceProperties properties) {
        return RestClient.builder().baseUrl(properties.endpoints().gmailBaseUrl()).build();
    }

    @Bean
    RestClient calendarRestClient(GoogleWorkspaceProperties properties) {
        return RestClient.builder().baseUrl(properties.endpoints().calendarBaseUrl()).build();
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
