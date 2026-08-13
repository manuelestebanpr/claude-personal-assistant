package com.my.custom.claudepersonalassistant.audit.logging;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
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

    private LogRecordBuilder record;
    private OpenTelemetryLogRecordAppender appender;

    @BeforeEach
    void setUp() {
        record = mock(LogRecordBuilder.class, RETURNS_SELF);
        Logger logger = mock(Logger.class);
        given(logger.logRecordBuilder()).willReturn(record);
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

        verify(record).setBody("Assistant stream completed");
        verify(record).setSeverity(Severity.INFO);
        verify(record).setSeverityText("INFO");
        verify(record).setTimestamp(when);
        verify(record).setAttribute(OpenTelemetryLogRecordAppender.CODE_NAMESPACE, LOGGER_NAME);
        verify(record).setAttribute(OpenTelemetryLogRecordAppender.THREAD_NAME, "task-1");
        verify(record).emit();
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

        verify(record).setAttribute(AttributeKey.longKey("chatId"), 42L);
        verify(record).setAttribute(AttributeKey.longKey("statusCode"), 429L);
        verify(record).setAttribute(AttributeKey.doubleKey("ratio"), 0.5d);
        verify(record).setAttribute(AttributeKey.booleanKey("retryable"), true);
        verify(record).setAttribute(AttributeKey.stringKey("classification"), "WARN");
    }

    @Test
    void skipsKeyValuePairsWithoutAValue() {
        LoggingEvent event = event(Level.INFO, "Assistant stream error");
        event.setKeyValuePairs(List.of(new KeyValuePair("errorType", null)));

        appender.append(event);

        verify(record, never()).setAttribute(AttributeKey.stringKey("errorType"), null);
        verify(record).emit();
    }

    @Test
    void promotesMdcEntriesToAttributes() {
        LoggingEvent event = event(Level.INFO, "Chat deleted");
        event.setMDCPropertyMap(Map.of("requestId", "r-1"));

        appender.append(event);

        verify(record).setAttribute(AttributeKey.stringKey("requestId"), "r-1");
    }

    @Test
    void recordsTheThrowableAsExceptionAttributes() {
        LoggingEvent event = event(Level.ERROR, "Assistant stream error",
                new IllegalStateException("rate limited"));

        appender.append(event);

        verify(record).setAttribute(OpenTelemetryLogRecordAppender.EXCEPTION_TYPE,
                IllegalStateException.class.getName());
        verify(record).setAttribute(OpenTelemetryLogRecordAppender.EXCEPTION_MESSAGE, "rate limited");
        verify(record).setAttribute(eq(OpenTelemetryLogRecordAppender.EXCEPTION_STACKTRACE),
                contains("IllegalStateException"));
    }

    /** Without the ambient context, Grafana cannot pivot from a Loki line to its Tempo trace. */
    @Test
    void attachesTheAmbientTraceContext() {
        appender.append(event(Level.INFO, "Content block transition"));

        verify(record).setContext(any());
    }

    private LoggingEvent event(Level level, String message) {
        return event(level, message, null);
    }

    private LoggingEvent event(Level level, String message, Throwable throwable) {
        // Built through a real LoggerContext so the event carries a usable MDC adapter, exactly
        // as it would in production.
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        return new LoggingEvent(ch.qos.logback.classic.Logger.FQCN, loggerContext.getLogger(LOGGER_NAME),
                level, message, throwable, null);
    }
}
