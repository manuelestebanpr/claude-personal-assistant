package com.my.custom.claudepersonalassistant.mcp.domain;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;
import com.my.custom.claudepersonalassistant.mcp.event.ToolInvokedEvent;

/**
 * Publishes MCP tool events inside their own transaction, attributed to the server that ran them.
 *
 * <p>Tools are invoked straight from the HTTP endpoint, which has no transaction of its own.
 * {@code @ApplicationModuleListener} is {@code AFTER_COMMIT}, so a publish with no transaction in
 * scope is silently dropped: the event never reaches the registry and the auditor never runs. The
 * assistant module wraps its own publishing for exactly this reason.
 */
@Component
class ToolEventPublisher {

    /**
     * Used when no configured server points at this application — the tools still ran here, and an
     * unattributed audit line is worse than one naming the id the loopback default uses.
     */
    static final String FALLBACK_SERVER_ID = "local";

    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final String serverId;

    ToolEventPublisher(ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager,
            McpProperties properties) {
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW rather than REQUIRED: a tool called from inside some future transactional
        // caller must still get its own committed publish, not one that a rollback elsewhere
        // could take down with it.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.serverId = resolveOwnServerId(properties);
    }

    void publish(Object event) {
        Object attributed = attribute(event);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(attributed));
    }

    /**
     * The registry runs a tool without knowing which id addresses this server — MCP never tells a
     * server what its clients call it — so the id is stamped on the way out, in the one place that
     * can see the configuration.
     */
    private Object attribute(Object event) {
        if (event instanceof ToolInvokedEvent invoked && invoked.serverId() == null) {
            return new ToolInvokedEvent(serverId, invoked.toolName(), invoked.failed());
        }
        return event;
    }

    /**
     * Read from the <em>client</em> list on purpose, even though this is the server side: the id has
     * to be the same string {@code McpServerConnection} logs and the composer's
     * {@code !<server>/<tool>} syntax accepts, or the audit line joins to nothing. {@code isLocal()}
     * classifies by the entry's actual host rather than by a naming convention, which is why the
     * entry can be found without assuming it is called "local" or that it is first.
     */
    private static String resolveOwnServerId(McpProperties properties) {
        return properties.servers().stream()
                .filter(McpProperties.Server::isLocal)
                .map(McpProperties.Server::id)
                .findFirst()
                .orElse(FALLBACK_SERVER_ID);
    }
}
