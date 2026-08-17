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

    /** The two MDC keys Micrometer fills; {@code setContext} already carries both natively. */
    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_SPAN_ID = "spanId";

    /**
     * Well under Loki's effective {@code max_structured_metadata_size} of 64KB, which rejects the
     * <em>whole</em> entry with HTTP 400 rather than trimming the offending field — so an
     * untruncated Anthropic-SDK-plus-Reactor cause chain costs the error line entirely. A constant
     * rather than a property: the cap belongs to the backend and this is the only class that knows
     * which field is at risk of hitting it.
     *
     * <p>A quarter of the cap, not a hair under it, because the limit counts bytes while this
     * counts characters — a non-ASCII exception message can cost three bytes each, and the
     * remaining headroom is what stops that difference from becoming a rejected entry.
     */
    static final int MAX_STACKTRACE_CHARACTERS = 16_384;

    static final String STACKTRACE_TRUNCATION_MARKER =
            "\n... [stack trace truncated by the OpenTelemetry appender to stay under the log "
                    + "backend's structured-metadata size limit]";

    private final LoggerProvider loggerProvider;

    OpenTelemetryLogRecordAppender(LoggerProvider loggerProvider) {
        this.loggerProvider = loggerProvider;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (isOpenTelemetryInternalLog(event)) {
            return;
        }
        LogRecordBuilder recordBuilder = loggerProvider.get(INSTRUMENTATION_SCOPE).logRecordBuilder();
        recordBuilder.setTimestamp(event.getInstant());
        // Correlates the record with the span in scope, which is what lets Grafana pivot from a
        // Loki line to its Tempo trace via the trace_id derived field.
        recordBuilder.setContext(Context.current());
        recordBuilder.setSeverity(severityOf(event.getLevel()));
        recordBuilder.setSeverityText(event.getLevel().toString());
        recordBuilder.setBody(event.getFormattedMessage());
        recordBuilder.setAttribute(CODE_NAMESPACE, event.getLoggerName());
        recordBuilder.setAttribute(THREAD_NAME, event.getThreadName());
        // MDC first, key-value pairs second, and the order is the whole point: both write through
        // setAttribute, an equal AttributeKey replaces whatever was there, and the application's
        // structured facts come from addKeyValue(...). Swap these and a colliding MDC key silently
        // overwrites the fact the dashboards actually query on.
        appendMdc(recordBuilder, event);
        appendKeyValuePairs(recordBuilder, event);
        appendThrowable(recordBuilder, event);
        recordBuilder.emit();
    }

    /**
     * {@code jul-to-slf4j} is on the classpath and Boot installs {@code SLF4JBridgeHandler}, so the
     * OTLP exporter's own failure records arrive at the root logger this appender is attached to.
     * Exporting them would feed the failing pipeline back into itself: every failed export logs,
     * every log is exported, and a collector outage gets louder from the inside.
     */
    private boolean isOpenTelemetryInternalLog(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        return loggerName != null && loggerName.startsWith("io.opentelemetry.");
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
    private void appendKeyValuePairs(LogRecordBuilder recordBuilder, ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) {
            return;
        }
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            setAttribute(recordBuilder, pair.key, pair.value);
        }
    }

    private void appendMdc(LogRecordBuilder recordBuilder, ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null) {
            return;
        }
        mdc.forEach((key, value) -> {
            // Micrometer mirrors the current trace into the MDC, but setContext() above already
            // carried it natively — that is what produces the trace_id/span_id Grafana's derived
            // field matches. Mapping these two as ordinary attributes ships every id twice.
            if (MDC_TRACE_ID.equals(key) || MDC_SPAN_ID.equals(key)) {
                return;
            }
            setAttribute(recordBuilder, key, value);
        });
    }

    private void appendThrowable(LogRecordBuilder recordBuilder, ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable == null) {
            return;
        }
        recordBuilder.setAttribute(EXCEPTION_TYPE, throwable.getClassName());
        recordBuilder.setAttribute(EXCEPTION_MESSAGE, throwable.getMessage());
        // Rendered from the proxy rather than the Throwable: a proxy is all that survives an event
        // that crossed a serialization boundary, and it renders suppressed and nested causes too.
        recordBuilder.setAttribute(EXCEPTION_STACKTRACE, truncated(ThrowableProxyUtil.asString(throwable)));
    }

    /**
     * Keeps the head verbatim — the top frames are the ones that identify the failure — and marks
     * the cut explicitly, so a reader can tell a truncated trace from a corrupted one.
     */
    private String truncated(String stacktrace) {
        if (stacktrace.length() <= MAX_STACKTRACE_CHARACTERS) {
            return stacktrace;
        }
        return stacktrace.substring(0, MAX_STACKTRACE_CHARACTERS) + STACKTRACE_TRUNCATION_MARKER;
    }

    private void setAttribute(LogRecordBuilder recordBuilder, String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        switch (value) {
            case String text -> recordBuilder.setAttribute(AttributeKey.stringKey(key), text);
            case Boolean flag -> recordBuilder.setAttribute(AttributeKey.booleanKey(key), flag);
            case Double number -> recordBuilder.setAttribute(AttributeKey.doubleKey(key), number);
            case Float number -> recordBuilder.setAttribute(AttributeKey.doubleKey(key), number.doubleValue());
            case Number number -> recordBuilder.setAttribute(AttributeKey.longKey(key), number.longValue());
            default -> recordBuilder.setAttribute(AttributeKey.stringKey(key), String.valueOf(value));
        }
    }
}
