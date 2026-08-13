package com.my.custom.claudepersonalassistant.audit.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Attaches the {@link OpenTelemetryLogRecordAppender} to Logback's root logger once the Spring
 * context is up.
 *
 * <p>Registering programmatically rather than from {@code logback-spring.xml} keeps Boot's console
 * logging untouched and avoids the static hand-off an XML-declared appender needs — Logback builds
 * appenders from XML long before there is a Spring context to take the {@code SdkLoggerProvider}
 * from. The trade-off is that records emitted before startup completes are not exported.
 */
@Component
class OpenTelemetryLoggingBridge implements InitializingBean, DisposableBean {

    static final String APPENDER_NAME = "openTelemetry";

    private final ObjectProvider<SdkLoggerProvider> loggerProviders;

    private OpenTelemetryLogRecordAppender appender;

    OpenTelemetryLoggingBridge(ObjectProvider<SdkLoggerProvider> loggerProviders) {
        this.loggerProviders = loggerProviders;
    }

    @Override
    public void afterPropertiesSet() {
        SdkLoggerProvider loggerProvider = loggerProviders.getIfAvailable();
        // Absent whenever OpenTelemetry is switched off, which is how every test context runs.
        if (loggerProvider == null || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        appender = new OpenTelemetryLogRecordAppender(loggerProvider);
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        appender.start();
        rootLogger(context).addAppender(appender);
    }

    @Override
    public void destroy() {
        if (appender == null) {
            return;
        }
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            rootLogger(context).detachAppender(appender);
        }
        appender.stop();
        appender = null;
    }

    private Logger rootLogger(LoggerContext context) {
        return context.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
