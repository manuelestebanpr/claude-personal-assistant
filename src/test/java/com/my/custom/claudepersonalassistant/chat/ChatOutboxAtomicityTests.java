package com.my.custom.claudepersonalassistant.chat;

import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.api.AssistantRegistry;
import com.my.custom.claudepersonalassistant.assistant.api.VisionClient;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantDescriptor;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.event.ChatCreatedEvent;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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

    /** The chat module boots alone here, so every assistant port it depends on has to be stood in for. */
    @MockitoBean
    private VisionClient visionClient;

    @MockitoBean
    private McpToolGateway toolGateway;

    @MockitoBean
    private AssistantRegistry assistantRegistry;

    @Autowired
    private ChatFacade chatFacade;

    @BeforeEach
    void resolveEveryAssistantToTheDefault() {
        given(assistantRegistry.resolve(any()))
                .willReturn(new AssistantDescriptor("default", "Personal Assistant", ""));
    }

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
