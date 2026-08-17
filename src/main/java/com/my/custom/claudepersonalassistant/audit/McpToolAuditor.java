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
 * <p>Counted here as well as inside the {@code mcp} module on purpose — see {@link AuditMetrics} for
 * why the two counters are not duplicates.
 *
 * <p>The log line names the server as well as the tool because a tool name is unique per server
 * only. Without it, {@code logs.json}'s tool-invocation panel cannot say which server ran a tool
 * whose name two servers happen to share.
 */
@Component
@RequiredArgsConstructor
class McpToolAuditor {

    private static final Logger log = LoggerFactory.getLogger(McpToolAuditor.class);

    /**
     * Separate from {@link AuditMetrics}' tag keys even where the spelling matches: a metric tag is
     * a cardinality decision and a log key is a query surface, and the two are free to diverge.
     */
    private static final String KEY_SERVER = "server";
    private static final String KEY_TOOL = "tool";
    private static final String KEY_OUTCOME = "outcome";

    private final MeterRegistry meterRegistry;

    @ApplicationModuleListener
    void onToolInvoked(ToolInvokedEvent event) {
        String outcome = event.failed() ? AuditMetrics.OUTCOME_FAILURE : AuditMetrics.OUTCOME_SUCCESS;
        log.atInfo()
                .addKeyValue(KEY_SERVER, event.serverId())
                .addKeyValue(KEY_TOOL, event.toolName())
                .addKeyValue(KEY_OUTCOME, outcome)
                .log("MCP tool invoked");
        meterRegistry.counter(AuditMetrics.TOOLS_INVOKED,
                AuditMetrics.TAG_TOOL, event.toolName(),
                AuditMetrics.TAG_OUTCOME, outcome).increment();
    }

    /** A call for a tool that does not exist means some client is holding a stale tool list. */
    @ApplicationModuleListener
    void onToolRejected(ToolInvocationRejectedEvent event) {
        log.atWarn()
                .addKeyValue(KEY_TOOL, event.toolName())
                .log("MCP tool call rejected: unknown tool");
        meterRegistry.counter(AuditMetrics.TOOLS_REJECTED, AuditMetrics.TAG_TOOL, event.toolName()).increment();
    }
}
