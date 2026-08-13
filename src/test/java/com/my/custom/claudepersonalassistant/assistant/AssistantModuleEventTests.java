package com.my.custom.claudepersonalassistant.assistant;

import java.time.Duration;
import java.util.List;

import com.anthropic.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Widened to include {@code audit} so {@code AssistantErrorAuditor} — the real, only
 * {@code @ApplicationModuleListener} consumer of {@link AssistantErrorEvent} — is present in
 * this test's context. Without it there is no listener for the event publication registry to
 * track, and the completion assertion below could never observe anything either way.
 */
@ApplicationModuleTest(extraIncludes = "audit")
class AssistantModuleEventTests {

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private AssistantClient assistantClient;

    @Autowired
    private EventPublicationRegistry registry;

    @Test
    void streamFailurePublishesClassifiedAssistantErrorEvent(Scenario scenario) {
        RateLimitException rateLimit = mock(RateLimitException.class);
        given(rateLimit.statusCode()).willReturn(429);
        given(rateLimit.getMessage()).willReturn("rate limited");
        // DefaultChatClientUtils.toChatClientRequest mutates the model's options unconditionally.
        given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.error(rateLimit));

        AssistantRequest request = new AssistantRequest(77L, List.of(), "hello");

        scenario.stimulate((Runnable) () -> {
                    try {
                        assistantClient.stream(request, delta -> { });
                    } catch (AssistantException ignored) {
                        // expected: the error path is what this test exercises
                    }
                })
                .andWaitForEventOfType(AssistantErrorEvent.class)
                .matching(event -> Long.valueOf(77L).equals(event.conversationId()))
                .toArriveAndVerify(event -> {
                    assertThat(event.classification()).isEqualTo(ErrorClassification.RETRYABLE);
                    assertThat(event.statusCode()).isEqualTo(429);
                });

        // The assertions above only prove ApplicationEventPublisher.publishEvent(...) was
        // called with the right payload — Scenario observes that via a thread-bound test
        // listener that fires independently of AFTER_COMMIT/registry gating. Separately prove
        // the real outbox mechanics: AssistantErrorPublisher wraps the publish in a
        // TransactionTemplate specifically so AssistantErrorAuditor (an
        // @ApplicationModuleListener, which inherits @TransactionalEventListener's unmodified
        // fallbackExecution=false) is not silently skipped. Without that wrapper the registry
        // row would still get written — Modulith stores it unconditionally, before any listener
        // runs — but it would sit incomplete forever, because the listener would never be
        // invoked. Waiting for completion is what actually distinguishes "wrapper present"
        // from "wrapper silently removed": deleting the wrapper leaves this assertion timing
        // out instead of passing.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registry.findIncompletePublications())
                        .noneMatch(publication -> publication.getEvent() instanceof AssistantErrorEvent));
    }
}
