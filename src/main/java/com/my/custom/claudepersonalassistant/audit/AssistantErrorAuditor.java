package com.my.custom.claudepersonalassistant.audit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;

/**
 * Audits classified assistant stream failures with an error log and a tagged counter.
 *
 * <p>This is deliberately the <em>only</em> error line for a failed turn: Spring AI's
 * {@code ErrorLoggingObservationHandler} is switched off in {@code observability.properties} because
 * it logged the same failure a second time, and a rate panel counting lines read one failure as two.
 * The one kept is this one because it is also the one that moves
 * {@code assistant_stream_errors_total}, so a line and a metric point always agree.
 *
 * <p>{@link AssistantErrorEvent} is a record of Strings and carries no {@code Throwable}, so the
 * appender's {@code appendThrowable()} never fires for an application error and its
 * {@code MAX_STACKTRACE_CHARACTERS} truncation only ever applies to framework-thrown lines.
 */
@Component
@RequiredArgsConstructor
class AssistantErrorAuditor {

    private static final Logger log = LoggerFactory.getLogger(AssistantErrorAuditor.class);

    /**
     * A constant body, with the variable text carried in {@link #KEY_DETAIL}. Interpolating the
     * provider's message into the body made every distinct upstream wording its own Loki body, so
     * nothing grouped and a rate panel over "this failure" could not be written. Same reason
     * {@code McpServerConnection} keeps its detail out of the message.
     */
    private static final String MESSAGE = "Assistant stream error";

    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_CLASSIFICATION = "classification";
    private static final String KEY_STATUS_CODE = "statusCode";
    private static final String KEY_ERROR_TYPE = "errorType";
    private static final String KEY_DETAIL = "detail";

    private final MeterRegistry meterRegistry;

    @ApplicationModuleListener
    void onAssistantError(AssistantErrorEvent event) {
        log.atError()
                .addKeyValue(KEY_CONVERSATION_ID, event.conversationId())
                .addKeyValue(KEY_CLASSIFICATION, event.classification())
                .addKeyValue(KEY_STATUS_CODE, event.statusCode())
                .addKeyValue(KEY_ERROR_TYPE, event.errorType())
                .addKeyValue(KEY_DETAIL, event.message())
                .log(MESSAGE);
        meterRegistry.counter(AuditMetrics.STREAM_ERRORS,
                        AuditMetrics.TAG_CLASSIFICATION, event.classification().name(),
                        AuditMetrics.TAG_ERROR_TYPE,
                        event.errorType() != null ? event.errorType() : AuditMetrics.TAG_VALUE_NONE)
                .increment();
    }
}
