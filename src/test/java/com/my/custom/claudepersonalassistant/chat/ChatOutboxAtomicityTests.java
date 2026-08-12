package com.my.custom.claudepersonalassistant.chat;

import java.util.Collection;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.AssistantClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the atomicity half of Spring Modulith's event publication registry: an event
 * published inside a transaction that rolls back must leave no row behind. This holds because
 * the registry's own JPA write is itself {@code @Transactional} (default propagation
 * REQUIRED), so it joins the caller's already-active transaction and rolls back with it,
 * rather than being written unconditionally.
 */
@ApplicationModuleTest
class ChatOutboxAtomicityTests {

    @MockitoBean
    private AssistantClient assistantClient;

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private EventPublicationRegistry registry;

    @Test
    void rolledBackConversationCreationLeavesNoEventPublicationRow(Scenario scenario) {
        scenario.stimulate(tx -> tx.execute(status -> {
                    chatFacade.createConversation();
                    status.setRollbackOnly();
                    return null;
                }))
                .andWaitForStateChange(() -> Boolean.TRUE, value -> value)
                .andVerify(ignored -> assertThat(incompletePublicationsOf(ChatCreatedEvent.class)).isEmpty());
    }

    private Collection<TargetEventPublication> incompletePublicationsOf(Class<?> eventType) {
        return registry.findIncompletePublications().stream()
                .filter(publication -> eventType.isInstance(publication.getEvent()))
                .toList();
    }
}
