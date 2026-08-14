package com.my.custom.claudepersonalassistant.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatCreatedEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatDeletedEvent;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
class AuditModuleTests {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void chatCreatedEventIncrementsCounter(Scenario scenario) {
        double before = counterValue(AuditMetrics.CHATS_CREATED);

        scenario.publish(new ChatCreatedEvent(1L, "a title"))
                .andWaitForStateChange(() -> counterValue(AuditMetrics.CHATS_CREATED), value -> value > before)
                .andVerify(value -> assertThat(value).isEqualTo(before + 1));
    }

    @Test
    void chatDeletedEventIncrementsCounter(Scenario scenario) {
        double before = counterValue(AuditMetrics.CHATS_DELETED);

        scenario.publish(new ChatDeletedEvent(1L, "a title"))
                .andWaitForStateChange(() -> counterValue(AuditMetrics.CHATS_DELETED), value -> value > before)
                .andVerify(value -> assertThat(value).isEqualTo(before + 1));
    }

    @Test
    void assistantErrorEventIncrementsTaggedCounter(Scenario scenario) {
        String[] tags = {
                AuditMetrics.TAG_CLASSIFICATION, ErrorClassification.RETRYABLE.name(),
                AuditMetrics.TAG_ERROR_TYPE, "rate_limit_error"
        };
        double before = counterValue(AuditMetrics.STREAM_ERRORS, tags);

        scenario.publish(new AssistantErrorEvent(9L, ErrorClassification.RETRYABLE, 429, "rate_limit_error",
                        "rate limited"))
                .andWaitForStateChange(() -> counterValue(AuditMetrics.STREAM_ERRORS, tags),
                        value -> value > before)
                .andVerify(value -> assertThat(value).isEqualTo(before + 1));
    }

    @Test
    void assistantErrorWithoutErrorTypeUsesNoneTag(Scenario scenario) {
        String[] tags = {
                AuditMetrics.TAG_CLASSIFICATION, ErrorClassification.UNKNOWN.name(),
                AuditMetrics.TAG_ERROR_TYPE, AuditMetrics.TAG_VALUE_NONE
        };
        double before = counterValue(AuditMetrics.STREAM_ERRORS, tags);

        scenario.publish(new AssistantErrorEvent(9L, ErrorClassification.UNKNOWN, null, null, "boom"))
                .andWaitForStateChange(() -> counterValue(AuditMetrics.STREAM_ERRORS, tags),
                        value -> value > before)
                .andVerify(value -> assertThat(value).isEqualTo(before + 1));
    }

    /**
     * Guards {@code META-INF/orm.xml}. Modulith writes the outbox row <em>before</em> the listener
     * runs, so an event whose JSON does not fit {@code SERIALIZED_EVENT} fails the publishing
     * transaction and the listener never fires. Hibernate sizes that column from
     * {@code JpaEventPublication}'s bare {@code @Column}, i.e. {@code VARCHAR(255)}, and
     * {@code AssistantErrorEvent} carries the provider's unbounded error message — a real 401
     * produced 277 characters and was dropped. Without the override this fails on the insert.
     */
    @Test
    void assistantErrorEventLongerThanTheDefaultColumnStillReachesTheListener(Scenario scenario) {
        String[] tags = {
                AuditMetrics.TAG_CLASSIFICATION, ErrorClassification.TERMINAL.name(),
                AuditMetrics.TAG_ERROR_TYPE, "authentication_error"
        };
        double before = counterValue(AuditMetrics.STREAM_ERRORS, tags);

        scenario.publish(new AssistantErrorEvent(9L, ErrorClassification.TERMINAL, 401,
                        "authentication_error", "x".repeat(2_000)))
                .andWaitForStateChange(() -> counterValue(AuditMetrics.STREAM_ERRORS, tags),
                        value -> value > before)
                .andVerify(value -> assertThat(value).isEqualTo(before + 1));
    }

    private double counterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
