package com.my.custom.claudepersonalassistant.mcp.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The MCP servers this application connects to as a client.
 *
 * <p>A list rather than a single endpoint: our own server is just the first entry, and adding a
 * third-party one is a property, not a code change. Configure with
 * {@code mcp.servers[0].id=…}; leaving the list empty falls back to the loopback default so the
 * application works out of the box.
 */
@ConfigurationProperties("mcp")
public record McpProperties(@DefaultValue List<Server> servers) {

    private static final Server LOCAL = new Server("local", "Claude Personal Assistant",
            "http://localhost:8080", "/mcp", Protocol.STATELESS, null, false);

    public McpProperties {
        servers = servers == null || servers.isEmpty() ? List.of(LOCAL) : List.copyOf(servers);
    }

    /**
     * @param id            stable identifier used to address the server; also what a tool call
     *                      names, so it must be unique
     * @param name          human-readable label for the picker
     * @param baseUrl       scheme, host and port
     * @param endpoint      path of the server's MCP endpoint
     * @param protocol      which revision it speaks — see {@link Protocol}
     * @param authorization value for an {@code Authorization} header, when the server wants one
     * @param googleAuth    when true, ignore {@code authorization} and instead authenticate every
     *                      request with a freshly-refreshed Google OAuth access token — needed
     *                      because that token expires in about an hour and this application runs
     *                      continuously, unlike a static bearer value
     */
    public record Server(
            String id,
            String name,
            @DefaultValue("http://localhost:8080") String baseUrl,
            @DefaultValue("/mcp") String endpoint,
            @DefaultValue("STATELESS") Protocol protocol,
            String authorization,
            @DefaultValue("false") boolean googleAuth) {

        public String url() {
            return baseUrl + endpoint;
        }

        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }

        /**
         * Classifies by the base URL's actual host, not by {@code id} — {@code id} is just a
         * convention (someone could rename the local server, or misname a remote one "local"), and
         * a {@code String.contains("localhost")} check would false-positive on a host like
         * {@code localhost.evil.com}. {@link URI} parsing avoids both traps.
         */
        public boolean isLocal() {
            if (baseUrl == null) {
                return false;
            }
            try {
                String host = URI.create(baseUrl).getHost();
                return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
            }
            catch (IllegalArgumentException malformed) {
                return false;
            }
        }
    }

    /**
     * MCP revisions differ most in whether a client has to open a session before it may ask
     * anything, so that is what the client has to pick between.
     */
    public enum Protocol {

        /**
         * Revision {@code 2026-07-28} and later: no {@code initialize}, no session. Every message
         * carries its own {@code _meta}, so the first request can be the useful one. This is what
         * our own server speaks.
         */
        STATELESS,

        /**
         * Revisions up to {@code 2025-11-25}: {@code initialize} then
         * {@code notifications/initialized} before anything else, and an {@code Mcp-Session-Id} to
         * echo on every later request. Most third-party servers still speak this.
         */
        SESSION
    }
}
