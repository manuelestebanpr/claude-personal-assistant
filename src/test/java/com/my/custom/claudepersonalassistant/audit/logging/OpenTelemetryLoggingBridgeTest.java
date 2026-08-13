package com.my.custom.claudepersonalassistant.audit.logging;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Spring Boot auto-configures the OTLP log exporter but installs no Logback appender, so the
 * bridge below is what makes log export happen at all.
 *
 * <p>Assertions compare the attached appenders before and after rather than looking one up by
 * name: the root logger is JVM-global, so a Spring context booted by another test in the same
 * fork may have installed an appender under the very same name.
 */
class OpenTelemetryLoggingBridgeTest {

    private OpenTelemetryLoggingBridge bridge;

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.destroy();
        }
    }

    @Test
    void attachesAnAppenderToTheRootLoggerWhenOpenTelemetryIsAvailable() {
        bridge = new OpenTelemetryLoggingBridge(providerOf(SdkLoggerProvider.builder().build()));
        List<Appender<ILoggingEvent>> before = attachedAppenders();

        bridge.afterPropertiesSet();

        List<Appender<ILoggingEvent>> installed = added(before);
        assertThat(installed).singleElement()
                .satisfies(appender -> assertThat(appender.getName())
                        .isEqualTo(OpenTelemetryLoggingBridge.APPENDER_NAME))
                .satisfies(appender -> assertThat(appender.isStarted()).isTrue());
    }

    @Test
    void detachesItsOwnAppenderOnShutdown() {
        bridge = new OpenTelemetryLoggingBridge(providerOf(SdkLoggerProvider.builder().build()));
        List<Appender<ILoggingEvent>> before = attachedAppenders();
        bridge.afterPropertiesSet();
        Appender<ILoggingEvent> installed = added(before).getFirst();

        bridge.destroy();

        assertThat(attachedAppenders()).doesNotContain(installed);
        assertThat(installed.isStarted()).isFalse();
    }

    /** Test contexts run with OpenTelemetry disabled; the bridge must not fail them. */
    @Test
    void doesNothingWhenNoLoggerProviderIsPresent() {
        bridge = new OpenTelemetryLoggingBridge(providerOf(null));
        List<Appender<ILoggingEvent>> before = attachedAppenders();

        bridge.afterPropertiesSet();

        assertThat(added(before)).isEmpty();
    }

    private List<Appender<ILoggingEvent>> added(List<Appender<ILoggingEvent>> before) {
        List<Appender<ILoggingEvent>> now = new ArrayList<>(attachedAppenders());
        now.removeAll(before);
        return now;
    }

    private List<Appender<ILoggingEvent>> attachedAppenders() {
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        rootLogger().iteratorForAppenders().forEachRemaining(appenders::add);
        return appenders;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<SdkLoggerProvider> providerOf(SdkLoggerProvider loggerProvider) {
        ObjectProvider<SdkLoggerProvider> objectProvider = mock(ObjectProvider.class);
        given(objectProvider.getIfAvailable()).willReturn(loggerProvider);
        return objectProvider;
    }

    private Logger rootLogger() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
