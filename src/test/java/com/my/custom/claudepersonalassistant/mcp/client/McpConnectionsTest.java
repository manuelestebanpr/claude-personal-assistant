package com.my.custom.claudepersonalassistant.mcp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.client.google.GoogleAccessTokens;
import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A {@code google-auth} server cannot carry a static {@code authorization} header — the token it
 * needs expires in about an hour and this application runs continuously — so these tests pin that
 * a fresh token is fetched on every outgoing request rather than once at startup, and that a
 * misconfigured server (the flag set with no token supplier available) fails fast rather than
 * sending an unauthenticated request.
 */
class McpConnectionsTest {

    /** Spring's own default client observation name; renaming it would break every Tempo query. */
    private static final String HTTP_CLIENT_OBSERVATION = "http.client.requests";

    private final Logger logger = (Logger) LoggerFactory.getLogger(McpConnections.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    /**
     * The line that would have caught the indexed-binder gap bug instantly: it shows exactly what
     * got wired from properties, so "1 server wired" instead of the expected 3 is visible the
     * moment the application starts, with no need to hit any endpoint first.
     */
    @Test
    void logsOneWiringLinePerServerPlusASummaryOfTheTotalCount() {
        McpProperties.Server local = new McpProperties.Server("local", "Local", "http://localhost:8080",
                "/mcp", McpProperties.Protocol.STATELESS, null, false);
        McpProperties.Server remote = new McpProperties.Server("gmail-mcp", "Gmail",
                "https://gmailmcp.googleapis.com", "/mcp/v1", McpProperties.Protocol.SESSION, "Bearer token", false);

        McpConnections.from(List.of(local, remote), JsonMapper.builder().build(), null, ObservationRegistry.NOOP);

        List<ILoggingEvent> wiringLines = eventsWithMessage(McpConnections.WIRING_MESSAGE);
        assertThat(wiringLines).hasSize(2);

        assertThat(keyValue(wiringLines.get(0), McpConnections.KEY_SERVER)).isEqualTo("local");
        assertThat(keyValue(wiringLines.get(0), McpConnections.KEY_CATEGORY)).isEqualTo(McpConnections.CATEGORY_LOCAL);
        assertThat(keyValue(wiringLines.get(0), McpConnections.KEY_PROTOCOL)).isEqualTo("STATELESS");
        assertThat(keyValue(wiringLines.get(0), McpConnections.KEY_URL)).isEqualTo("http://localhost:8080/mcp");

        assertThat(keyValue(wiringLines.get(1), McpConnections.KEY_SERVER)).isEqualTo("gmail-mcp");
        assertThat(keyValue(wiringLines.get(1), McpConnections.KEY_CATEGORY)).isEqualTo(McpConnections.CATEGORY_REMOTE);
        assertThat(keyValue(wiringLines.get(1), McpConnections.KEY_PROTOCOL)).isEqualTo("SESSION");
        assertThat(keyValue(wiringLines.get(1), McpConnections.KEY_URL)).isEqualTo("https://gmailmcp.googleapis.com/mcp/v1");

        List<ILoggingEvent> summary = eventsWithMessage(McpConnections.SUMMARY_MESSAGE);
        assertThat(summary).hasSize(1);
        assertThat(keyValue(summary.getFirst(), McpConnections.KEY_COUNT)).isEqualTo(2);
    }

    private List<ILoggingEvent> eventsWithMessage(String message) {
        return appender.list.stream().filter(event -> message.equals(event.getMessage())).toList();
    }

    private Object keyValue(ILoggingEvent event, String key) {
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            if (key.equals(pair.key)) {
                return pair.value;
            }
        }
        return null;
    }

    @Test
    void refreshesTheBearerTokenOnEveryOutgoingRequest() throws IOException {
        GoogleAccessTokens tokens = mock(GoogleAccessTokens.class);
        when(tokens.current()).thenReturn("token-1", "token-2");
        ClientHttpRequestInterceptor interceptor = McpConnections.googleAuthInterceptor(tokens);

        HttpRequest request = mock(HttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        byte[] body = new byte[0];

        interceptor.intercept(request, body, execution);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-1");

        headers.clear();
        interceptor.intercept(request, body, execution);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-2");

        // Twice, not once at startup: a cached header would eventually be sent expired.
        verify(execution, times(2)).execute(request, body);
    }

    @Test
    void failsFastWhenAGoogleAuthServerHasNoTokenSupplierConfigured() {
        McpProperties.Server server = new McpProperties.Server("gmail-mcp", "Gmail",
                "https://gmailmcp.googleapis.com", "/mcp/v1", McpProperties.Protocol.SESSION, null, true);

        assertThatThrownBy(() -> McpConnections.from(List.of(server), JsonMapper.builder().build(), null, ObservationRegistry.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gmail-mcp")
                .hasMessageContaining("google.workspace.enabled=true");
    }

    @Test
    void keepsTheStaticAuthorizationHeaderForANonGoogleServer() {
        McpProperties.Server server = new McpProperties.Server("example", "Example",
                "https://mcp.example.com", "/mcp", McpProperties.Protocol.SESSION, "Bearer static-token", false);

        // Building must not require a GoogleAccessTokens when the server does not ask for one.
        List<McpServerConnection> connections = McpConnections.from(List.of(server), JsonMapper.builder().build(),
                null, ObservationRegistry.NOOP);

        assertThat(connections).singleElement().satisfies(connection -> assertThat(connection.id()).isEqualTo("example"));
    }

    /**
     * {@code RestClient.builder()} leaves its observation registry at {@code NOOP}, and this Boot
     * has no {@code spring-boot-restclient} module to customise it back — so an unwired client
     * records no client observation, emits no {@code traceparent}, and every {@code POST /mcp}
     * lands in Tempo as a parentless root trace unrelated to the request that caused it.
     *
     * <p>Asserted against a closed loopback port rather than a live stub: the observation has to be
     * recorded whether the call succeeds or fails, and a refused connection needs no server.
     */
    @Test
    void recordsAClientObservationForEveryOutgoingRequest() throws IOException {
        TestObservationRegistry registry = TestObservationRegistry.create();
        McpProperties.Server server = new McpProperties.Server("local", "Local", deadLoopbackBaseUrl(),
                "/mcp", McpProperties.Protocol.STATELESS, null, false);

        RestClient restClient = McpConnections.restClient(server, null, registry);
        assertThatThrownBy(() -> restClient.get().uri(server.url()).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(HTTP_CLIENT_OBSERVATION);
    }

    /** A port that was bound just long enough to be certain nothing else is listening on it. */
    private String deadLoopbackBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }
}
