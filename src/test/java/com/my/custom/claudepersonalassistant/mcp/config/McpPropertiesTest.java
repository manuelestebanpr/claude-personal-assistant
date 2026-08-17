package com.my.custom.claudepersonalassistant.mcp.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the shape {@code mcp.servers[]} binds into — a config property with no test is a property
 * nobody notices breaking.
 */
class McpPropertiesTest {

    @Test
    void defaultsGoogleAuthToFalseWhenNotConfigured() {
        McpProperties properties = bind(Map.of(
                "mcp.servers[0].id", "example",
                "mcp.servers[0].base-url", "https://mcp.example.com"));

        assertThat(properties.servers()).singleElement()
                .extracting(McpProperties.Server::googleAuth).isEqualTo(false);
    }

    @Test
    void bindsGoogleAuthFromTheKebabCaseProperty() {
        McpProperties properties = bind(Map.of(
                "mcp.servers[0].id", "gmail-mcp",
                "mcp.servers[0].base-url", "https://gmailmcp.googleapis.com",
                "mcp.servers[0].endpoint", "/mcp/v1",
                "mcp.servers[0].protocol", "SESSION",
                "mcp.servers[0].google-auth", "true"));

        assertThat(properties.servers()).singleElement().satisfies(server -> {
            assertThat(server.googleAuth()).isTrue();
            assertThat(server.protocol()).isEqualTo(McpProperties.Protocol.SESSION);
        });
    }

    /**
     * Pins a real Spring Boot gotcha in {@code IndexedElementsBinder}, found the hard way in
     * production when {@code mcp.servers[0]} was commented out while {@code [1]}/{@code [2]}
     * stayed active — verified empirically against this exact record on Spring Boot 4.1, both
     * through this {@link Binder} and through a full {@code @EnableConfigurationProperties}
     * {@code ApplicationContextRunner}.
     *
     * <p>Note this is <b>not</b> a silent fallback to the single local default: a gap at index 0
     * with {@code [1]}/{@code [2]} still populated leaves {@code mcp.servers[1].*} and
     * {@code mcp.servers[2].*} as orphaned properties that {@code IndexedElementsBinder} never
     * attempts (it stops at the first missing index), and {@code assertNoUnboundChildren} then
     * notices those leftovers and throws {@link UnboundConfigurationPropertiesException} — so
     * binding fails loudly and a real application context refuses to start. That is a better
     * outcome than a silent fallback would be, but it is still worth pinning: if this binder
     * behaviour ever changes to silently tolerate the gap instead of throwing, that would be the
     * actually-silent, actually-dangerous failure mode, and this test would catch it.
     */
    @Test
    void aGapAtIndexZeroFailsBindingLoudlyInsteadOfSilentlyDroppingLaterIndexedServers() {
        Map<String, String> source = Map.of(
                "mcp.servers[1].id", "gmail-mcp",
                "mcp.servers[1].base-url", "https://gmailmcp.googleapis.com",
                "mcp.servers[1].protocol", "SESSION",
                "mcp.servers[2].id", "calendar-mcp",
                "mcp.servers[2].base-url", "https://calendarmcp.googleapis.com",
                "mcp.servers[2].protocol", "SESSION");

        assertThatThrownBy(() -> bind(source))
                .isInstanceOf(BindException.class)
                .cause()
                .isInstanceOf(UnboundConfigurationPropertiesException.class)
                .hasMessageContaining("mcp.servers[1]")
                .hasMessageContaining("mcp.servers[2]");
    }

    @Test
    void classifiesLocalhostBaseUrlAsLocal() {
        assertThat(server("http://localhost:8080").isLocal()).isTrue();
    }

    @Test
    void classifiesLoopbackIpBaseUrlAsLocal() {
        assertThat(server("http://127.0.0.1:8080").isLocal()).isTrue();
    }

    @Test
    void classifiesGmailMcpBaseUrlAsRemote() {
        assertThat(server("https://gmailmcp.googleapis.com").isLocal()).isFalse();
    }

    @Test
    void classifiesCalendarMcpBaseUrlAsRemote() {
        assertThat(server("https://calendarmcp.googleapis.com").isLocal()).isFalse();
    }

    /**
     * A near-miss host that merely contains the substring {@code localhost} must not false-positive
     * — the classification has to compare the parsed host, not do a {@code contains} check.
     */
    @Test
    void doesNotFalsePositiveOnAHostThatOnlyContainsLocalhostAsASubstring() {
        assertThat(server("https://localhost.evil.com").isLocal()).isFalse();
    }

    private McpProperties.Server server(String baseUrl) {
        return new McpProperties.Server("example", "Example", baseUrl, "/mcp",
                McpProperties.Protocol.STATELESS, null, false);
    }

    private McpProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("mcp", Bindable.of(McpProperties.class))
                .get();
    }
}
