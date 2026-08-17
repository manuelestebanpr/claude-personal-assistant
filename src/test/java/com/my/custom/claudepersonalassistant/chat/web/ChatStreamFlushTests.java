package com.my.custom.claudepersonalassistant.chat.web;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * Proves the streaming endpoint reaches the network <em>incrementally</em>, over a real socket.
 *
 * <p>This is the one regression no other test in the suite can catch. {@code ChatStreamControllerTest}
 * drives {@code MockMvc}, whose {@code MockHttpServletResponse} is an in-memory byte buffer with no
 * socket and no notion of when a flush happened — the final aggregate body is byte-identical whether
 * the server flushed once per line or once at the end. {@code ChatModuleIntegrationTests} collects
 * events into a list and never touches HTTP at all. So a fully buffered stream, which is exactly what
 * the browser reported, passed every existing test.
 *
 * <p>The invariant asserted here is arrival <em>spread</em>, not absolute latency: the mocked
 * assistant emits its deltas {@link #DELTA_INTERVAL} apart, so a streaming server delivers the first
 * and last lines at least {@code (deltas - 1) * DELTA_INTERVAL} apart, while a buffering server
 * delivers every line in the same instant once generation has finished. That distinction is large and
 * does not depend on machine speed.
 *
 * @see ChatStreamController for why the flush goes through {@code HttpServletResponse#flushBuffer()}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamFlushTests {

    private static final Duration DELTA_INTERVAL = Duration.ofMillis(300);
    private static final List<String> DELTAS = List.of("Streaming ", "arrives ", "one ", "line at a time.");

    /**
     * How much of the emitted spread a streaming server must reproduce at the socket. Below 1.0 to
     * absorb scheduler jitter; far above the ~0 a buffering server produces.
     */
    private static final double MINIMUM_SPREAD_RATIO = 0.5;

    @LocalServerPort
    private int port;

    @Autowired
    private ChatFacade chatFacade;

    @MockitoBean
    private AssistantClient assistantClient;

    @Test
    void deltasReachTheSocketAsTheyAreProducedRatherThanAllAtTheEnd() throws Exception {
        emitDeltasSlowly();
        ConversationDto conversation = chatFacade.createConversation();

        List<Duration> arrivals = readStreamRecordingArrivalTimes(conversation.id());

        assertThat(arrivals)
                .as("one NDJSON line per delta, plus the DONE line")
                .hasSize(DELTAS.size() + 1);

        Duration spread = arrivals.getLast().minus(arrivals.getFirst());
        Duration emittedSpread = DELTA_INTERVAL.multipliedBy(DELTAS.size() - 1);
        Duration minimumSpread = Duration.ofMillis((long) (emittedSpread.toMillis() * MINIMUM_SPREAD_RATIO));

        assertThat(spread)
                .as("""
                        First and last NDJSON lines arrived %d ms apart, but the assistant emitted its \
                        deltas over %d ms. A spread near zero means the whole body was buffered and \
                        released at once — the browser then shows nothing until generation finishes. \
                        See ChatStreamController: OutputStream.flush() is a no-op on Spring's \
                        NonFlushingOutputStream wrapper.""".formatted(spread.toMillis(), emittedSpread.toMillis()))
                .isGreaterThanOrEqualTo(minimumSpread);
    }

    /** Mocks the assistant so each delta is handed to the sink {@link #DELTA_INTERVAL} after the last. */
    private void emitDeltasSlowly() {
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            for (String delta : DELTAS) {
                Thread.sleep(DELTA_INTERVAL);
                onDelta.accept(delta);
            }
            return null;
        }).given(assistantClient).stream(any(), any());
    }

    /**
     * Reads the NDJSON response line by line off a real socket, timing each line's arrival relative
     * to the moment the request was sent.
     */
    private List<Duration> readStreamRecordingArrivalTimes(Long chatId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/chats/" + chatId + "/messages/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"Hi\"}"))
                .build();

        List<Duration> arrivals = new ArrayList<>();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            long sentAt = System.nanoTime();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (!line.isBlank()) {
                        arrivals.add(Duration.ofNanos(System.nanoTime() - sentAt));
                    }
                }
            }
        }
        return arrivals;
    }
}
