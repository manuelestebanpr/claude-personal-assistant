package com.my.custom.claudepersonalassistant.audit.logging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The bridge from Logback to the OpenTelemetry logs API is hand-written because the upstream
 * appender has no released (non-alpha) version, so its mapping is pinned by these tests.
 */
class OpenTelemetryLogRecordAppenderTest {

    private static final String LOGGER_NAME = "com.example.Sample";

    private LogRecordBuilder recordBuilder;
    private OpenTelemetryLogRecordAppender appender;

    @BeforeEach
    void setUp() {
        recordBuilder = mock(LogRecordBuilder.class, RETURNS_SELF);
        Logger logger = mock(Logger.class);
        given(logger.logRecordBuilder()).willReturn(recordBuilder);
        LoggerProvider loggerProvider = mock(LoggerProvider.class);
        given(loggerProvider.get(anyString())).willReturn(logger);
        appender = new OpenTelemetryLogRecordAppender(loggerProvider);
    }

    @Test
    void emitsBodySeverityAndOriginOfTheEvent() {
        Instant when = Instant.parse("2026-08-12T21:07:40Z");
        LoggingEvent event = event(Level.INFO, "Assistant stream completed");
        event.setInstant(when);
        event.setThreadName("task-1");

        appender.append(event);

        verify(recordBuilder).setBody("Assistant stream completed");
        verify(recordBuilder).setSeverity(Severity.INFO);
        verify(recordBuilder).setSeverityText("INFO");
        verify(recordBuilder).setTimestamp(when);
        verify(recordBuilder).setAttribute(OpenTelemetryLogRecordAppender.CODE_NAMESPACE, LOGGER_NAME);
        verify(recordBuilder).setAttribute(OpenTelemetryLogRecordAppender.THREAD_NAME, "task-1");
        verify(recordBuilder).emit();
    }

    @Test
    void mapsEveryLogbackLevelOntoASeverity() {
        assertThat(OpenTelemetryLogRecordAppender.severityOf(Level.TRACE)).isEqualTo(Severity.TRACE);
        assertThat(OpenTelemetryLogRecordAppender.severityOf(Level.DEBUG)).isEqualTo(Severity.DEBUG);
        assertThat(OpenTelemetryLogRecordAppender.severityOf(Level.INFO)).isEqualTo(Severity.INFO);
        assertThat(OpenTelemetryLogRecordAppender.severityOf(Level.WARN)).isEqualTo(Severity.WARN);
        assertThat(OpenTelemetryLogRecordAppender.severityOf(Level.ERROR)).isEqualTo(Severity.ERROR);
    }

    /**
     * The whole application logs through {@code log.atInfo().addKeyValue(...)}, so dropping
     * key-value pairs would leave Loki with unstructured message bodies and nothing to query on.
     */
    @Test
    void promotesKeyValuePairsToTypedAttributes() {
        LoggingEvent event = event(Level.INFO, "Chat created");
        event.setKeyValuePairs(List.of(
                new KeyValuePair("chatId", 42L),
                new KeyValuePair("statusCode", 429),
                new KeyValuePair("ratio", 0.5d),
                new KeyValuePair("retryable", true),
                new KeyValuePair("classification", Level.WARN)));

        appender.append(event);

        verify(recordBuilder).setAttribute(AttributeKey.longKey("chatId"), 42L);
        verify(recordBuilder).setAttribute(AttributeKey.longKey("statusCode"), 429L);
        verify(recordBuilder).setAttribute(AttributeKey.doubleKey("ratio"), 0.5d);
        verify(recordBuilder).setAttribute(AttributeKey.booleanKey("retryable"), true);
        verify(recordBuilder).setAttribute(AttributeKey.stringKey("classification"), "WARN");
    }

    @Test
    void skipsKeyValuePairsWithoutAValue() {
        LoggingEvent event = event(Level.INFO, "Assistant stream error");
        event.setKeyValuePairs(List.of(new KeyValuePair("errorType", null)));

        appender.append(event);

        verify(recordBuilder, never()).setAttribute(AttributeKey.stringKey("errorType"), null);
        verify(recordBuilder).emit();
    }

    @Test
    void promotesMdcEntriesToAttributes() {
        LoggingEvent event = event(Level.INFO, "Chat deleted");
        event.setMDCPropertyMap(Map.of("requestId", "r-1"));

        appender.append(event);

        verify(recordBuilder).setAttribute(AttributeKey.stringKey("requestId"), "r-1");
    }

    @Test
    void recordsTheThrowableAsExceptionAttributes() {
        LoggingEvent event = event(Level.ERROR, "Assistant stream error",
                new IllegalStateException("rate limited"));

        appender.append(event);

        verify(recordBuilder).setAttribute(OpenTelemetryLogRecordAppender.EXCEPTION_TYPE,
                IllegalStateException.class.getName());
        verify(recordBuilder).setAttribute(OpenTelemetryLogRecordAppender.EXCEPTION_MESSAGE, "rate limited");
        verify(recordBuilder).setAttribute(eq(OpenTelemetryLogRecordAppender.EXCEPTION_STACKTRACE),
                contains("IllegalStateException"));
    }

    /** Without the ambient context, Grafana cannot pivot from a Loki line to its Tempo trace. */
    @Test
    void attachesTheAmbientTraceContext() {
        appender.append(event(Level.INFO, "Content block transition"));

        verify(recordBuilder).setContext(any());
    }

    /**
     * The application's ~50 {@code addKeyValue(...)} call sites are the only structured facts Loki
     * can be queried on, so a colliding MDC key must not be able to overwrite one. Both land on the
     * same {@link io.opentelemetry.api.common.AttributeKey}, and the last write wins — so the
     * key-value pairs have to be written last.
     */
    @Test
    void writesKeyValuePairsAfterMdcSoACollidingMdcKeyCannotOverwriteThem() {
        LoggingEvent event = event(Level.INFO, "Tool invoked");
        event.setKeyValuePairs(List.of(new KeyValuePair("requestId", "from-key-value")));
        event.setMDCPropertyMap(Map.of("requestId", "from-mdc"));

        LogRecordData emitted = emitThroughRealSdk(event);

        assertThat(emitted.getAttributes().get(AttributeKey.stringKey("requestId"))).isEqualTo("from-key-value");
    }

    /**
     * The record already carries the native OTel context, which is what produces the
     * {@code trace_id}/{@code span_id} Grafana's derived field matches on. Micrometer also mirrors
     * them into the MDC, so mapping the MDC blindly ships every id twice.
     */
    @Test
    void doesNotReEmitTheTraceIdsTheContextAlreadyCarries() {
        LoggingEvent event = event(Level.INFO, "Assistant stream started");
        event.setMDCPropertyMap(Map.of("traceId", "0af7651916cd43dd", "spanId", "b7ad6b7169203331",
                "requestId", "r-1"));

        LogRecordData emitted = emitThroughRealSdk(event);

        assertThat(emitted.getAttributes().get(AttributeKey.stringKey("traceId"))).isNull();
        assertThat(emitted.getAttributes().get(AttributeKey.stringKey("spanId"))).isNull();
        // The rest of the MDC still has to arrive; this is a two-key exclusion, not a switch-off.
        assertThat(emitted.getAttributes().get(AttributeKey.stringKey("requestId"))).isEqualTo("r-1");
    }

    /**
     * {@code jul-to-slf4j} is on the classpath and Boot installs {@code SLF4JBridgeHandler}, so the
     * OTLP exporter's own failure records reach the root logger this appender attaches to. Without
     * this exclusion a collector outage feeds itself: every failed export logs, every log is
     * exported, and the pipeline gets noisier exactly while it is already failing.
     */
    @Test
    void dropsOpenTelemetrysOwnRecordsRatherThanFeedingThemBackIntoTheExporter() {
        LoggingEvent event = eventFromLogger(Level.WARN, "Failed to export logs. Server responded with HTTP 503",
                "io.opentelemetry.exporter.internal.http.HttpExporter");

        appender.append(event);

        verify(recordBuilder, never()).emit();
    }

    /**
     * Loki's effective {@code max_structured_metadata_size} is 64KB and it rejects the <em>whole</em>
     * entry with HTTP 400 when a field exceeds it — so an untruncated Anthropic-SDK-plus-Reactor
     * cause chain loses the one line that mattered. Cut here, with a visible marker, because the
     * appender is the only place that knows which field is at risk.
     */
    @Test
    void truncatesAStackTraceThatWouldBlowTheBackendsStructuredMetadataLimit() {
        LoggingEvent event = event(Level.ERROR, "Assistant stream error", deeplyNestedThrowable());

        LogRecordData emitted = emitThroughRealSdk(event);

        String stacktrace = emitted.getAttributes().get(OpenTelemetryLogRecordAppender.EXCEPTION_STACKTRACE);
        assertThat(stacktrace).endsWith(OpenTelemetryLogRecordAppender.STACKTRACE_TRUNCATION_MARKER)
                .hasSize(OpenTelemetryLogRecordAppender.MAX_STACKTRACE_CHARACTERS
                        + OpenTelemetryLogRecordAppender.STACKTRACE_TRUNCATION_MARKER.length())
                // The head of the trace survives verbatim: the truncation must not corrupt what is
                // kept, or the top frames stop matching the source.
                .startsWith("java.lang.IllegalStateException");
    }

    @Test
    void leavesAStackTraceThatFitsUntouched() {
        LoggingEvent event = event(Level.ERROR, "Assistant stream error", shallowThrowable());

        LogRecordData emitted = emitThroughRealSdk(event);

        assertThat(emitted.getAttributes().get(OpenTelemetryLogRecordAppender.EXCEPTION_STACKTRACE))
                .doesNotContain(OpenTelemetryLogRecordAppender.STACKTRACE_TRUNCATION_MARKER);
    }

    /**
     * Frames replaced by hand rather than left as thrown: a throwable created inside JUnit carries
     * the whole reflective launcher stack, which is itself large enough to be truncated and would
     * make this test assert the opposite of what it says.
     */
    private Throwable shallowThrowable() {
        IllegalStateException throwable = new IllegalStateException("rate limited");
        throwable.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.Sample", "run", "Sample.java", 12) });
        return throwable;
    }

    /** Deep enough that {@link ThrowableProxyUtil#asString} comfortably exceeds the cut-off. */
    private Throwable deeplyNestedThrowable() {
        Throwable throwable = new IllegalStateException("root cause");
        for (int level = 0; level < 100; level++) {
            throwable = new IllegalStateException("nested failure %d %s".formatted(level, "x".repeat(200)), throwable);
        }
        return throwable;
    }

    /**
     * The mocked builder cannot show a later attribute replacing an earlier one, which is precisely
     * the behaviour under test, so these cases run against a real {@code SdkLoggerProvider} with an
     * in-process processor instead of the exporter.
     */
    private LogRecordData emitThroughRealSdk(LoggingEvent event) {
        List<LogRecordData> emitted = new ArrayList<>();
        SdkLoggerProvider provider = SdkLoggerProvider.builder()
                .addLogRecordProcessor((context, logRecord) -> emitted.add(logRecord.toLogRecordData()))
                .build();
        new OpenTelemetryLogRecordAppender(provider).append(event);
        assertThat(emitted).hasSize(1);
        return emitted.getFirst();
    }

    private LoggingEvent event(Level level, String message) {
        return event(level, message, null);
    }

    private LoggingEvent eventFromLogger(Level level, String message, String loggerName) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        return new LoggingEvent(ch.qos.logback.classic.Logger.FQCN, loggerContext.getLogger(loggerName),
                level, message, null, null);
    }

    private LoggingEvent event(Level level, String message, Throwable throwable) {
        // Built through a real LoggerContext so the event carries a usable MDC adapter, exactly
        // as it would in production.
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        return new LoggingEvent(ch.qos.logback.classic.Logger.FQCN, loggerContext.getLogger(LOGGER_NAME),
                level, message, throwable, null);
    }
}
