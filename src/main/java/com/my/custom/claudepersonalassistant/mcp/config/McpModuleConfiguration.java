package com.my.custom.claudepersonalassistant.mcp.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the MCP module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpModuleConfiguration {

    /** Injected into tools rather than read statically, so their output is testable. */
    @Bean
    Clock mcpClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Built from a fresh builder rather than the auto-configured one: {@code
     * spring-boot-starter-webmvc} brings the server side only, so no {@code RestClient.Builder}
     * bean exists to inject and asking for one stops the whole context from starting.
     */
    @Bean
    RestClient mcpRestClient(McpProperties properties) {
        return RestClient.builder().baseUrl(properties.client().url()).build();
    }
}
