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
 * name, because the root logger is JVM-global and this test mutates it. That used to be masking a
 * real duplicate: the {@code SdkLoggerProvider} is gated on {@code @ConditionalOnEnabledOpenTelemetry}
 * ({@code management.opentelemetry.enabled}, {@code matchIfMissing=true}) and <em>not</em> on the
 * export switches, so every cached Spring test context really did install a second appender under
 * the same name. {@code src/test/resources/application.properties} now sets {@code
 * management.opentelemetry.enabled=false}, so no context installs one at all — the before/after
 * diff stays because a shared mutable root logger deserves it, not because a duplicate is expected.
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

    /**
     * The state every test context is actually in now that {@code management.opentelemetry.enabled}
     * is false there: no {@code SdkLoggerProvider} bean, so the bridge has to no-op rather than
     * fail the context.
     */
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
