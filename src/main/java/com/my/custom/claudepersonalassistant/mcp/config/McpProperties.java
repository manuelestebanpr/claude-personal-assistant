package com.my.custom.claudepersonalassistant.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the MCP module.
 *
 * @param client where the outbound gateway sends its requests. It defaults to this application's
 *               own endpoint over loopback; moving the server into a service of its own is a
 *               change to {@code base-url} and nothing else
 */
@ConfigurationProperties("mcp")
public record McpProperties(@DefaultValue Client client) {

    /**
     * @param baseUrl  scheme, host and port of the MCP server
     * @param endpoint path of its single MCP endpoint
     */
    public record Client(
            @DefaultValue("http://localhost:8080") String baseUrl,
            @DefaultValue("/mcp") String endpoint) {

        public String url() {
            return baseUrl + endpoint;
        }
    }
}
