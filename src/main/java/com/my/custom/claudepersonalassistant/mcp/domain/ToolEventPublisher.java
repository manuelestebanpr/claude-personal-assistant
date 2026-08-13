package com.my.custom.claudepersonalassistant.mcp.domain;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes MCP tool events inside their own transaction.
 *
 * <p>Tools are invoked straight from the HTTP endpoint, which has no transaction of its own.
 * {@code @ApplicationModuleListener} is {@code AFTER_COMMIT}, so a publish with no transaction in
 * scope is silently dropped: the event never reaches the registry and the auditor never runs. The
 * assistant module wraps its own publishing for exactly this reason.
 */
@Component
class ToolEventPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    ToolEventPublisher(ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager) {
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW rather than REQUIRED: a tool called from inside some future transactional
        // caller must still get its own committed publish, not one that a rollback elsewhere
        // could take down with it.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void publish(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }
}
