package com.my.custom.claudepersonalassistant.audit.logging;

import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import org.slf4j.event.KeyValuePair;

/**
 * Bridges Logback events onto the OpenTelemetry logs API so they reach the OTLP exporter Spring
 * Boot already auto-configures.
 *
 * <p>Boot builds an {@code SdkLoggerProvider}, a {@code BatchLogRecordProcessor} and an OTLP
 * exporter, but ships and installs <em>no</em> appender — nothing feeds that pipeline, so log
 * export silently does nothing. The upstream
 * {@code io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0} would fill the gap
 * but has no released version (61 published, every one {@code -alpha}), so this maps the events
 * directly against {@code io.opentelemetry.api.logs}, GA since 1.27.0 and documented as the API
 * for writing exactly this kind of appender.
 */
final class OpenTelemetryLogRecordAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    static final String INSTRUMENTATION_SCOPE = "com.my.custom.claudepersonalassistant";

    static final AttributeKey<String> CODE_NAMESPACE = AttributeKey.stringKey("code.namespace");
    static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");
    static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private final LoggerProvider loggerProvider;

    OpenTelemetryLogRecordAppender(LoggerProvider loggerProvider) {
        this.loggerProvider = loggerProvider;
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogRecordBuilder record = loggerProvider.get(INSTRUMENTATION_SCOPE).logRecordBuilder();
        record.setTimestamp(event.getInstant());
        // Correlates the record with the span in scope, which is what lets Grafana pivot from a
        // Loki line to its Tempo trace via the trace_id derived field.
        record.setContext(Context.current());
        record.setSeverity(severityOf(event.getLevel()));
        record.setSeverityText(event.getLevel().toString());
        record.setBody(event.getFormattedMessage());
        record.setAttribute(CODE_NAMESPACE, event.getLoggerName());
        record.setAttribute(THREAD_NAME, event.getThreadName());
        appendKeyValuePairs(record, event);
        appendMdc(record, event);
        appendThrowable(record, event);
        record.emit();
    }

    static Severity severityOf(Level level) {
        return switch (level.toInt()) {
            case Level.TRACE_INT -> Severity.TRACE;
            case Level.DEBUG_INT -> Severity.DEBUG;
            case Level.INFO_INT -> Severity.INFO;
            case Level.WARN_INT -> Severity.WARN;
            case Level.ERROR_INT -> Severity.ERROR;
            default -> Severity.UNDEFINED_SEVERITY_NUMBER;
        };
    }

    /**
     * The application logs structured facts through {@code log.atInfo().addKeyValue(...)}; without
     * this the backend would receive message bodies with nothing to filter on.
     */
    private void appendKeyValuePairs(LogRecordBuilder record, ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) {
            return;
        }
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            setAttribute(record, pair.key, pair.value);
        }
    }

    private void appendMdc(LogRecordBuilder record, ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null) {
            return;
        }
        mdc.forEach((key, value) -> setAttribute(record, key, value));
    }

    private void appendThrowable(LogRecordBuilder record, ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable == null) {
            return;
        }
        record.setAttribute(EXCEPTION_TYPE, throwable.getClassName());
        record.setAttribute(EXCEPTION_MESSAGE, throwable.getMessage());
        // Rendered from the proxy rather than the Throwable: a proxy is all that survives an event
        // that crossed a serialization boundary, and it renders suppressed and nested causes too.
        record.setAttribute(EXCEPTION_STACKTRACE, ThrowableProxyUtil.asString(throwable));
    }

    private void setAttribute(LogRecordBuilder record, String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        switch (value) {
            case String text -> record.setAttribute(AttributeKey.stringKey(key), text);
            case Boolean flag -> record.setAttribute(AttributeKey.booleanKey(key), flag);
            case Double number -> record.setAttribute(AttributeKey.doubleKey(key), number);
            case Float number -> record.setAttribute(AttributeKey.doubleKey(key), number.doubleValue());
            case Number number -> record.setAttribute(AttributeKey.longKey(key), number.longValue());
            default -> record.setAttribute(AttributeKey.stringKey(key), String.valueOf(value));
        }
    }
}
