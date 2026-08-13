package com.my.custom.claudepersonalassistant.mcp;

import java.time.Duration;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.my.custom.claudepersonalassistant.audit.AuditMetrics;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Includes {@code audit} so {@code McpToolAuditor} — the only
 * {@code @ApplicationModuleListener} consumer of the tool events — is present.
 */
@ApplicationModuleTest(extraIncludes = "audit")
class McpModuleEventTests {

    private static final String TOOL = "get_current_hour";

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

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

    private double invocationsRecordedByAudit() {
        Counter counter = meterRegistry.find(AuditMetrics.TOOLS_INVOKED)
                .tags(AuditMetrics.TAG_TOOL, TOOL, AuditMetrics.TAG_OUTCOME, AuditMetrics.OUTCOME_SUCCESS)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
