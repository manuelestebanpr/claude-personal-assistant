package com.my.custom.claudepersonalassistant.audit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.event.ToolInvocationRejectedEvent;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvokedEvent;

/**
 * Audits MCP tool usage with structured logs and tagged counters.
 *
 * <p>Counted here as well as inside the {@code mcp} module on purpose: the module measures its own
 * latency, while this records the fact for the same reason chat lifecycle events are recorded —
 * one place to read what the assistant actually did.
 */
@Component
@RequiredArgsConstructor
class McpToolAuditor {

    private static final Logger log = LoggerFactory.getLogger(McpToolAuditor.class);

    private final MeterRegistry meterRegistry;

    @ApplicationModuleListener
    void onToolInvoked(ToolInvokedEvent event) {
        String outcome = event.failed() ? AuditMetrics.OUTCOME_FAILURE : AuditMetrics.OUTCOME_SUCCESS;
        log.atInfo()
                .addKeyValue("tool", event.toolName())
                .addKeyValue("outcome", outcome)
                .log("MCP tool invoked");
        meterRegistry.counter(AuditMetrics.TOOLS_INVOKED,
                AuditMetrics.TAG_TOOL, event.toolName(),
                AuditMetrics.TAG_OUTCOME, outcome).increment();
    }

    /** A call for a tool that does not exist means some client is holding a stale tool list. */
    @ApplicationModuleListener
    void onToolRejected(ToolInvocationRejectedEvent event) {
        log.atWarn()
                .addKeyValue("tool", event.toolName())
                .log("MCP tool call rejected: unknown tool");
        meterRegistry.counter(AuditMetrics.TOOLS_REJECTED, AuditMetrics.TAG_TOOL, event.toolName()).increment();
    }
}
