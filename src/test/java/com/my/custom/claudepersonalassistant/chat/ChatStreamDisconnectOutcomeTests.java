package com.my.custom.claudepersonalassistant.chat;

import java.util.List;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.api.VisionClient;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.config.ChatMetrics;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * A turn the client abandoned is not a turn that succeeded.
 *
 * <p>The interesting moment is the terminal DONE line: every delta has already been written, so
 * the assistant call itself completed, and the write that fails is the last one. It fails with an
 * {@code UncheckedIOException} from the controller's writer rather than with an
 * {@code AssistantException} — the stream protocol's own failure type — so before the outer
 * {@code catch (RuntimeException)} existed it escaped every handler on the way out and the turn was
 * tagged {@code outcome=success}. That is precisely the signal the timer and the span were added to
 * give.
 */
@ApplicationModuleTest
class ChatStreamDisconnectOutcomeTests {

    @MockitoBean
    private AssistantClient assistantClient;

    /** The chat module boots alone here, so every assistant port it depends on has to be stood in for. */
    @MockitoBean
    private VisionClient visionClient;

    @MockitoBean
    private McpToolGateway toolGateway;

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void countsATurnTheClientAbandonedOnTheDoneLineAsAFailure() {
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("partial");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();
        // Deltas rather than absolute counts: the meter registry belongs to a Spring context that
        // other tests in this module share, so this turn's contribution is the only thing this test
        // can honestly own.
        long failuresBefore = streamTurnCount(ChatMetrics.OUTCOME_FAILURE);
        long successesBefore = streamTurnCount(ChatMetrics.OUTCOME_SUCCESS);

        assertThatThrownBy(() -> chatFacade.prepareTurn(chat.id(), "hello", List.of()).stream(event -> {
            if (event.type() == StreamEvent.Type.DONE) {
                throw new UncheckedIOException(new IOException("Broken pipe"));
            }
        })).isInstanceOf(UncheckedIOException.class);

        assertThat(streamTurnCount(ChatMetrics.OUTCOME_FAILURE)).isEqualTo(failuresBefore + 1);
        assertThat(streamTurnCount(ChatMetrics.OUTCOME_SUCCESS)).isEqualTo(successesBefore);
    }

    private long streamTurnCount(String outcome) {
        Timer timer = meterRegistry.find(ChatMetrics.MODULE_OPERATION)
                .tag(ChatMetrics.TAG_OPERATION, ChatMetrics.OPERATION_STREAM_TURN)
                .tag(ChatMetrics.TAG_OUTCOME, outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
