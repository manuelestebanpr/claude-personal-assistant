package com.my.custom.claudepersonalassistant.assistant.error;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;

/**
 * Publishes {@link AssistantErrorEvent}s when a stream fails.
 */
@Component
public class AssistantErrorPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    AssistantErrorPublisher(ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager) {
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Explicit, not just correct-by-accident: the current caller (a blocking catch block on
        // a virtual thread) never has a bound transaction, so PROPAGATION_REQUIRED would behave
        // identically here. But this is a public method with no restriction on future
        // callers — REQUIRES_NEW guarantees this publish always gets its own independent,
        // always-attempted commit, even if a future caller invokes it from inside another
        // @Transactional method.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void publish(AssistantErrorEvent event) {
        // The calling virtual thread has no bound transaction here. @ApplicationModuleListener is
        // AFTER_COMMIT, so without an active transaction the event would silently be dropped and
        // never reach the JPA event publication registry.
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }
}
