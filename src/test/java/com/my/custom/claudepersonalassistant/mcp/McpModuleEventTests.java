package com.my.custom.claudepersonalassistant.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.my.custom.claudepersonalassistant.audit.AuditMetrics;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolRegistry;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvokedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

/**
 * Includes {@code audit} so {@code McpToolAuditor} — the only
 * {@code @ApplicationModuleListener} consumer of the tool events — is present.
 */
@ApplicationModuleTest(extraIncludes = "audit")
class McpModuleEventTests {

    private static final String TOOL = "get_current_hour";
    private static final String LOCAL_SERVER_ID = "local";

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private InvokedEvents invokedEvents;

    /**
     * Invokes the tool directly rather than through {@code Scenario}, which runs its stimulus
     * inside a transaction and would therefore hide the very failure this guards against: tools
     * are called straight from the HTTP endpoint with no transaction in scope, and
     * {@code @ApplicationModuleListener} is {@code AFTER_COMMIT}, so a publish that is not wrapped
     * in its own transaction is dropped without a trace.
     *
     * <p>Asserting on the auditor's counter rather than on the publication registry is what makes
     * that observable: the registry row is written either way, but the counter only moves if the
     * listener actually ran.
     */
    @Test
    void aToolInvokedOutsideATransactionStillReachesItsListener() {
        double before = invocationsRecordedByAudit();

        toolRegistry.invoke(TOOL, Map.of());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(invocationsRecordedByAudit()).isEqualTo(before + 1));
    }

    /**
     * A tool name is unique per server only, so an event naming just the tool cannot be joined back
     * to the server that ran it. The registry cannot supply the id — MCP never tells a server what
     * its clients call it — so {@code ToolEventPublisher} stamps it, and this pins that it reaches
     * the listener rather than arriving null.
     */
    @Test
    void anInvokedEventNamesTheServerThatRanTheTool() {
        invokedEvents.recorded().clear();

        toolRegistry.invoke(TOOL, Map.of());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(invokedEvents.recorded())
                        .extracting(ToolInvokedEvent::serverId, ToolInvokedEvent::toolName)
                        .contains(tuple(LOCAL_SERVER_ID, TOOL)));
    }

    private double invocationsRecordedByAudit() {
        Counter counter = meterRegistry.find(AuditMetrics.TOOLS_INVOKED)
                .tags(AuditMetrics.TAG_TOOL, TOOL, AuditMetrics.TAG_OUTCOME, AuditMetrics.OUTCOME_SUCCESS)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * A plain {@code @EventListener} rather than an {@code @ApplicationModuleListener}: this one
     * observes the event as published, without the outbox round trip, which is what makes it a check
     * on the publisher's attribution and not a second copy of the delivery test above.
     */
    @TestConfiguration
    static class RecordingConfiguration {

        @Bean
        InvokedEvents invokedEvents() {
            return new InvokedEvents();
        }
    }

    static class InvokedEvents {

        private final List<ToolInvokedEvent> recorded = new ArrayList<>();

        @EventListener
        void on(ToolInvokedEvent event) {
            recorded.add(event);
        }

        List<ToolInvokedEvent> recorded() {
            return recorded;
        }
    }
}
