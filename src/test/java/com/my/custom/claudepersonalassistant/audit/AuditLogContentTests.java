package com.my.custom.claudepersonalassistant.audit;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatCreatedEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatDeletedEvent;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvokedEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what the audit listeners actually put on the wire.
 *
 * <p>These are not cosmetic assertions. {@code OpenTelemetryLoggingBridge} attaches the OTLP
 * appender to the root logger, so whatever these lines carry is shipped to Loki and retained for 31
 * days — which makes the message body a grouping decision and every key-value a data-retention one.
 * Both are easy to undo by "tidying" a log statement, and neither failure is visible from the code.
 */
class AuditLogContentTests {

    private static final String TITLE = "Why does my deployment keep failing at 3am";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ListAppender<ILoggingEvent> captured;
    private Logger attachedTo;

    @AfterEach
    void detach() {
        if (attachedTo != null) {
            attachedTo.detachAppender(captured);
        }
    }

    /**
     * The provider's error text used to be interpolated into the body, which made every distinct
     * upstream wording its own Loki body: nothing grouped, and "rate of this failure" could not be
     * expressed as a query. The body has to stay constant and the text has to travel as a fact.
     */
    @Test
    void anAssistantErrorLogsAConstantBodyAndCarriesTheProviderTextAsAFact() {
        capture(AssistantErrorAuditor.class);

        new AssistantErrorAuditor(meterRegistry).onAssistantError(new AssistantErrorEvent(
                9L, ErrorClassification.RETRYABLE, 429, "rate_limit_error", "rate limited, retry in 4s"));

        ILoggingEvent event = onlyEvent();
        assertThat(event.getFormattedMessage()).isEqualTo("Assistant stream error");
        assertThat(keyValues(event)).contains(
                new KeyValuePair("detail", "rate limited, retry in 4s"),
                new KeyValuePair("classification", ErrorClassification.RETRYABLE));
    }

    /**
     * A chat title is derived from the user's first message, so logging it shipped the opening of
     * every conversation to the log backend. With Spring AI's prompt and completion logging off,
     * this was the last path by which message content reached Loki; {@code chatId} is the join key
     * and the title stays in the database.
     */
    @Test
    void chatLifecycleLinesCarryNoUserContent() {
        capture(ChatLifecycleAuditor.class);
        ChatLifecycleAuditor auditor = new ChatLifecycleAuditor(meterRegistry);

        auditor.onChatCreated(new ChatCreatedEvent(1L, TITLE));
        auditor.onChatDeleted(new ChatDeletedEvent(1L, TITLE));

        assertThat(captured.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TITLE);
            assertThat(keyValues(event)).extracting(pair -> pair.value)
                    .doesNotContain((Object) TITLE);
            assertThat(keyValues(event)).extracting(pair -> pair.key).containsExactly("chatId");
        });
    }

    /**
     * Two servers may both expose a tool called {@code search}, so the server id is what keeps the
     * tool-invocation panel readable — and joinable to the picker's {@code !<server>/<tool>} syntax.
     */
    @Test
    void aToolInvocationLineNamesTheServerAsWellAsTheTool() {
        capture(McpToolAuditor.class);

        new McpToolAuditor(meterRegistry).onToolInvoked(
                new ToolInvokedEvent("local", "get_current_hour", false));

        assertThat(keyValues(onlyEvent())).contains(
                new KeyValuePair("server", "local"),
                new KeyValuePair("tool", "get_current_hour"),
                new KeyValuePair("outcome", AuditMetrics.OUTCOME_SUCCESS));
    }

    private void capture(Class<?> loggedBy) {
        captured = new ListAppender<>();
        captured.start();
        attachedTo = (Logger) LoggerFactory.getLogger(loggedBy);
        attachedTo.addAppender(captured);
    }

    private ILoggingEvent onlyEvent() {
        assertThat(captured.list).hasSize(1);
        return captured.list.getFirst();
    }

    private List<KeyValuePair> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs() == null ? List.of() : event.getKeyValuePairs();
    }
}
