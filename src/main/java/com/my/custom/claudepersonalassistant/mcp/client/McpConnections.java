package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.client.google.GoogleAccessTokens;
import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;

/**
 * Builds a {@link McpServerConnection} for each configured server.
 *
 * <p>A factory rather than a constructor because choosing the wire client from the configured
 * revision is the one decision that has to happen exactly once per server, at startup.
 */
public final class McpConnections {

    static final String WIRING_MESSAGE = "Wiring MCP server";
    static final String SUMMARY_MESSAGE = "MCP servers wired";

    static final String KEY_SERVER = "server";
    static final String KEY_CATEGORY = "category";
    static final String KEY_PROTOCOL = "protocol";
    static final String KEY_URL = "url";
    static final String KEY_COUNT = "count";

    static final String CATEGORY_LOCAL = "local";
    static final String CATEGORY_REMOTE = "remote";

    private static final Logger log = LoggerFactory.getLogger(McpConnections.class);

    private McpConnections() {
    }

    /**
     * @param googleAccessTokens  supplier for a {@code google-auth} server's bearer token; {@code
     *                            null} when {@code google.workspace.enabled} is off, which is fine
     *                            as long as no configured server actually asks for it
     * @param observationRegistry threaded in from the context because a {@link RestClient} built
     *                            without one silently observes nothing — see {@link #restClient}
     */
    public static List<McpServerConnection> from(List<McpProperties.Server> servers,
            ObjectMapper objectMapper, GoogleAccessTokens googleAccessTokens,
            ObservationRegistry observationRegistry) {
        List<McpServerConnection> connections = servers.stream()
                .map(server -> {
                    logWiring(server);
                    return new McpServerConnection(server,
                            wireClient(server, googleAccessTokens, observationRegistry), objectMapper);
                })
                .toList();
        log.atInfo()
                .addKeyValue(KEY_COUNT, connections.size())
                .log(SUMMARY_MESSAGE);
        return connections;
    }

    /**
     * The line that would have caught the indexed-binder gap bug instantly: it shows exactly what
     * got bound from {@code mcp.servers[…]} at startup, unconditionally, with no error or warning
     * needed for a silently-dropped server to be visible.
     */
    private static void logWiring(McpProperties.Server server) {
        log.atInfo()
                .addKeyValue(KEY_SERVER, server.id())
                .addKeyValue(KEY_CATEGORY, server.isLocal() ? CATEGORY_LOCAL : CATEGORY_REMOTE)
                .addKeyValue(KEY_PROTOCOL, server.protocol().name())
                .addKeyValue(KEY_URL, server.url())
                .log(WIRING_MESSAGE);
    }

    private static McpWireClient wireClient(McpProperties.Server server, GoogleAccessTokens googleAccessTokens,
            ObservationRegistry observationRegistry) {
        RestClient restClient = restClient(server, googleAccessTokens, observationRegistry);
        return switch (server.protocol()) {
            case STATELESS -> new StatelessWireClient(restClient, server.url());
            case SESSION -> new SessionWireClient(restClient, server.url());
        };
    }

    /**
     * A fresh builder per server, for the reason the module has always used one: {@code
     * spring-boot-starter-webmvc} brings the server side only, so no {@code RestClient.Builder}
     * bean exists to inject and asking for one stops the whole context from starting.
     *
     * <p>That absent <em>bean</em> is exactly why the registry has to be passed in by hand.
     * {@code RestClient.builder()} starts at {@link ObservationRegistry#NOOP}, and with no
     * {@code spring-boot-restclient} module on the classpath there is no auto-configured
     * customizer to put the real one back — so an unwired client records no client observation,
     * sends no {@code traceparent}, and every outbound MCP call shows up in Tempo as a parentless
     * root trace with no link to the request that caused it. Do not "simplify" this away.
     *
     * <p>Package-visible so that wiring is assertable without a live server.
     */
    static RestClient restClient(McpProperties.Server server, GoogleAccessTokens googleAccessTokens,
            ObservationRegistry observationRegistry) {
        RestClient.Builder builder = RestClient.builder().observationRegistry(observationRegistry);
        if (server.googleAuth()) {
            if (googleAccessTokens == null) {
                throw new IllegalStateException(("MCP server '%s' has google-auth=true but no Google OAuth "
                        + "credentials are available. Set google.workspace.enabled=true with valid "
                        + "google.workspace.client-id/client-secret/refresh-token, or turn google-auth off "
                        + "for this server.").formatted(server.id()));
            }
            // Not a static header: the access token expires in about an hour and this application
            // runs continuously, so it has to be fetched fresh on every outgoing request instead.
            builder.requestInterceptor(googleAuthInterceptor(googleAccessTokens));
        }
        else if (StringUtils.hasText(server.authorization())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, server.authorization());
        }
        return builder.build();
    }

    /** Package-visible so the refresh-per-request behaviour is testable without a live server. */
    static ClientHttpRequestInterceptor googleAuthInterceptor(GoogleAccessTokens googleAccessTokens) {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(googleAccessTokens.current());
            return execution.execute(request, body);
        };
    }
}
