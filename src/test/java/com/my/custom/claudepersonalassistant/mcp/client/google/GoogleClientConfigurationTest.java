package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code RestClient.builder()} starts at {@code ObservationRegistry.NOOP} and this Boot has no
 * {@code spring-boot-restclient} module to customise it back, so a client built without the
 * registry records nothing and sends no {@code traceparent} — the token refresh, the Gmail call
 * and the Calendar call all disappear from the trace of the tool invocation that made them.
 *
 * <p>Each client is driven against a closed loopback port: the observation has to be recorded
 * whether the call succeeds or fails, and a refused connection needs no stub server.
 */
class GoogleClientConfigurationTest {

    /** Spring's own default client observation name; renaming it would break every Tempo query. */
    private static final String HTTP_CLIENT_OBSERVATION = "http.client.requests";

    @Test
    void observesTheTokenClient() throws IOException {
        TestObservationRegistry registry = TestObservationRegistry.create();

        assertObserved(registry, configuration(properties()).googleTokenRestClient(registry));
    }

    @Test
    void observesTheGmailClient() throws IOException {
        TestObservationRegistry registry = TestObservationRegistry.create();
        GoogleWorkspaceProperties properties = properties();

        assertObserved(registry, configuration(properties).gmailRestClient(properties, registry));
    }

    @Test
    void observesTheCalendarClient() throws IOException {
        TestObservationRegistry registry = TestObservationRegistry.create();
        GoogleWorkspaceProperties properties = properties();

        assertObserved(registry, configuration(properties).calendarRestClient(properties, registry));
    }

    private void assertObserved(TestObservationRegistry registry, RestClient client) throws IOException {
        String url = deadLoopbackBaseUrl() + "/anything";
        assertThatThrownBy(() -> client.get().uri(url).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);

        TestObservationRegistryAssert.assertThat(registry).hasObservationWithNameEqualTo(HTTP_CLIENT_OBSERVATION);
    }

    /** A port that was bound just long enough to be certain nothing else is listening on it. */
    private String deadLoopbackBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private GoogleClientConfiguration configuration(GoogleWorkspaceProperties properties) {
        return new GoogleClientConfiguration(properties);
    }

    /**
     * The constructor rejects blank credentials, so all three have to be present even though no
     * call in this test ever reaches Google.
     */
    private GoogleWorkspaceProperties properties() {
        return new GoogleWorkspaceProperties(true, "client-id", "client-secret", "refresh-token",
                new GoogleWorkspaceProperties.Endpoints("http://127.0.0.1:1/token", "http://127.0.0.1:1/gmail",
                        "http://127.0.0.1:1/calendar", "primary"),
                new GoogleWorkspaceProperties.Limits(5, 20, 4000));
    }
}
