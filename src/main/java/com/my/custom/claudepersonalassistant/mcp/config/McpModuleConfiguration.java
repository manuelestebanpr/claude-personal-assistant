package com.my.custom.claudepersonalassistant.mcp.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.my.custom.claudepersonalassistant.mcp.client.McpConnections;
import com.my.custom.claudepersonalassistant.mcp.client.McpServerConnection;
import com.my.custom.claudepersonalassistant.mcp.client.google.GoogleAccessTokens;

/**
 * Wiring of the MCP module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpModuleConfiguration {

    /** Injected into tools rather than read statically, so their output is testable. */
    @Bean
    Clock mcpClock() {
        return Clock.system(ZoneId.of("America/Bogota"));
    }

    /**
     * One connection per configured server, in configuration order.
     *
     * <p>Built here rather than discovered: a server is a line of configuration, not a bean, so
     * there is nothing for component scanning to find.
     *
     * <p>{@link GoogleAccessTokens} is looked up via {@link ObjectProvider} rather than injected
     * directly because its bean only exists when {@code google.workspace.enabled=true} — this bean
     * has to build regardless, since most configurations have no {@code google-auth} server at all.
     *
     * <p>{@link ObservationRegistry} is injected as a hard dependency rather than defaulted to
     * {@code NOOP}: without it every outbound MCP call is untraced and unparented, which is exactly
     * the failure this wiring exists to prevent, and a silent {@code NOOP} fallback would hide the
     * day the bean disappears. It is a third-party library type, not another module, so it does not
     * widen this module's {@code allowedDependencies}.
     */
    @Bean
    List<McpServerConnection> mcpServerConnections(McpProperties properties, ObjectMapper objectMapper,
            ObjectProvider<GoogleAccessTokens> googleAccessTokens, ObservationRegistry observationRegistry) {
        return McpConnections.from(properties.servers(), objectMapper, googleAccessTokens.getIfAvailable(),
                observationRegistry);
    }
}
