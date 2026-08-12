package com.my.custom.claudepersonalassistant.audit;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import com.my.custom.claudepersonalassistant.chat.ChatCreatedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the retry half of the event publication registry: a listener that throws leaves its
 * own row incomplete rather than the exception being silently swallowed by {@code @Async} —
 * the precondition for {@code spring.modulith.events.republish-outstanding-events-on-restart}
 * to mean anything. Failure is per-listener: the real {@link ChatLifecycleAuditor} still
 * completes normally for the same event, published once.
 */
@ApplicationModuleTest
class AuditModuleListenerFailureTests {

    @Autowired
    private EventPublicationRegistry registry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void listenerFailureLeavesItsPublicationIncompleteWithoutAffectingOtherListeners(Scenario scenario) {
        double before = counterValue(AuditMetrics.CHATS_CREATED);

        // publish(...) is a lazy builder: the stimulus only runs once a terminal method is
        // chained onto it, so this trivial wait is what actually triggers the publish.
        scenario.publish(new ChatCreatedEvent(1L, "a title"))
                .andWaitForStateChange(() -> Boolean.TRUE, value -> value);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(counterValue(AuditMetrics.CHATS_CREATED)).isEqualTo(before + 1);
            assertThat(registry.findIncompletePublications())
                    .anyMatch(publication -> publication.getEvent() instanceof ChatCreatedEvent);
        });
    }

    private double counterValue(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingListenerConfiguration {

        @Bean
        FailingListener failingListener() {
            return new FailingListener();
        }
    }

    static class FailingListener {

        @ApplicationModuleListener
        void onChatCreated(ChatCreatedEvent event) {
            throw new IllegalStateException("simulated listener failure");
        }
    }
}
