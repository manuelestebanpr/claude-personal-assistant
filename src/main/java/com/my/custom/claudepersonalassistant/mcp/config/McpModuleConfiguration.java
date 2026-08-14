package com.my.custom.claudepersonalassistant.mcp.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.my.custom.claudepersonalassistant.mcp.client.McpConnections;
import com.my.custom.claudepersonalassistant.mcp.client.McpServerConnection;

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
     */
    @Bean
    List<McpServerConnection> mcpServerConnections(McpProperties properties, ObjectMapper objectMapper) {
        return McpConnections.from(properties.servers(), objectMapper);
    }
}
