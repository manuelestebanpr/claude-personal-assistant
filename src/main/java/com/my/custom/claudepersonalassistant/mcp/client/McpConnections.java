package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;

import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;

/**
 * Builds a {@link McpServerConnection} for each configured server.
 *
 * <p>A factory rather than a constructor because choosing the wire client from the configured
 * revision is the one decision that has to happen exactly once per server, at startup.
 */
public final class McpConnections {

    private McpConnections() {
    }

    public static List<McpServerConnection> from(List<McpProperties.Server> servers,
            ObjectMapper objectMapper) {
        return servers.stream()
                .map(server -> new McpServerConnection(server, wireClient(server), objectMapper))
                .toList();
    }

    private static McpWireClient wireClient(McpProperties.Server server) {
        RestClient restClient = restClient(server);
        return switch (server.protocol()) {
            case STATELESS -> new StatelessWireClient(restClient, server.url());
            case SESSION -> new SessionWireClient(restClient, server.url());
        };
    }

    /**
     * A fresh builder per server, for the reason the module has always used one: {@code
     * spring-boot-starter-webmvc} brings the server side only, so no {@code RestClient.Builder}
     * bean exists to inject and asking for one stops the whole context from starting.
     */
    private static RestClient restClient(McpProperties.Server server) {
        RestClient.Builder builder = RestClient.builder();
        if (StringUtils.hasText(server.authorization())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, server.authorization());
        }
        return builder.build();
    }
}
