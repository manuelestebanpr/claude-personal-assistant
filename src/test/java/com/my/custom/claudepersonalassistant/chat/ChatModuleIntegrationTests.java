package com.my.custom.claudepersonalassistant.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.AssistantException;
import com.my.custom.claudepersonalassistant.assistant.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.ClassifiedError;
import com.my.custom.claudepersonalassistant.assistant.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.HistoryRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ApplicationModuleTest
class ChatModuleIntegrationTests {

    @MockitoBean
    private AssistantClient assistantClient;

    @Autowired
    private ChatFacade chatFacade;

    @Test
    void creatingAChatPublishesChatCreatedEvent(Scenario scenario) {
        scenario.stimulate(() -> chatFacade.createConversation())
                .andWaitForEventOfType(ChatCreatedEvent.class)
                .toArriveAndVerify((event, created) -> {
                    assertThat(event.chatId()).isEqualTo(created.id());
                    assertThat(event.title()).isEqualTo(created.title());
                });
    }

    @Test
    void deletingAChatPublishesChatDeletedEventAndRemovesIt(Scenario scenario) {
        ConversationDto created = chatFacade.createConversation();

        scenario.stimulate((Runnable) () -> chatFacade.deleteConversation(created.id()))
                .andWaitForEventOfType(ChatDeletedEvent.class)
                .matching(event -> event.chatId().equals(created.id()))
                .toArriveAndVerify(event -> assertThat(chatFacade.listConversations())
                        .noneMatch(conversation -> conversation.id().equals(created.id())));
    }

    @Test
    void happyPathStreamPersistsBothMessagesAndRehydratesOnReopen() {
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("Hello");
            onDelta.accept(" world");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        List<StreamEvent> events = new ArrayList<>();
        chatFacade.prepareTurn(chat.id(), "Hi there").stream(events::add);

        assertThat(events).extracting(StreamEvent::type)
                .containsExactly(StreamEvent.Type.DELTA, StreamEvent.Type.DELTA, StreamEvent.Type.DONE);

        ConversationView reopened = chatFacade.openConversation(chat.id());
        assertThat(reopened.messages())
                .extracting(ChatMessageDto::role, ChatMessageDto::content)
                .containsExactly(
                        tuple(MessageRole.USER, "Hi there"),
                        tuple(MessageRole.ASSISTANT, "Hello world"));
        assertThat(reopened.conversation().title()).isEqualTo("Hi there");

        // The next turn replays the persisted history as model context.
        chatFacade.prepareTurn(chat.id(), "And again").stream(event -> { });
        ArgumentCaptor<AssistantRequest> requests = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistantClient, times(2)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().getLast().history()).containsExactly(
                new HistoryMessage(HistoryRole.USER, "Hi there"),
                new HistoryMessage(HistoryRole.ASSISTANT, "Hello world"));
    }

    @Test
    void failedStreamPersistsUserMessageAndPartialAnswerAndEmitsErrorEvent() {
        AssistantException failure = new AssistantException(
                new ClassifiedError(ErrorClassification.RETRYABLE, 429, "rate_limit_error", "rate limited"),
                new RuntimeException("boom"));
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("partial");
            throw failure;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        List<StreamEvent> events = new ArrayList<>();
        chatFacade.prepareTurn(chat.id(), "Hi").stream(events::add);

        assertThat(events).extracting(StreamEvent::type)
                .containsExactly(StreamEvent.Type.DELTA, StreamEvent.Type.ERROR);
        StreamEvent error = events.getLast();
        assertThat(error.classification()).isEqualTo(ErrorClassification.RETRYABLE);
        assertThat(error.message()).isEqualTo("rate limited");

        ConversationView reopened = chatFacade.openConversation(chat.id());
        assertThat(reopened.messages())
                .extracting(ChatMessageDto::role, ChatMessageDto::content)
                .containsExactly(
                        tuple(MessageRole.USER, "Hi"),
                        tuple(MessageRole.ASSISTANT, "partial"));
    }

    @Test
    void failedStreamWithoutAnyDeltaPersistsOnlyTheUserMessage() {
        AssistantException failure = new AssistantException(
                new ClassifiedError(ErrorClassification.TERMINAL, 401, "authentication_error", "bad key"),
                new RuntimeException("boom"));
        willAnswer(invocation -> {
            throw failure;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        List<StreamEvent> events = new ArrayList<>();
        chatFacade.prepareTurn(chat.id(), "Hi").stream(events::add);

        assertThat(events).extracting(StreamEvent::type).containsExactly(StreamEvent.Type.ERROR);
        assertThat(chatFacade.openConversation(chat.id()).messages())
                .extracting(ChatMessageDto::role, ChatMessageDto::content)
                .containsExactly(tuple(MessageRole.USER, "Hi"));
    }

    @Test
    void fullHistoryIsReplayedWhenWindowSizeIsZero() {
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("ok");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        chatFacade.prepareTurn(chat.id(), "m1").stream(event -> { });
        chatFacade.prepareTurn(chat.id(), "m2").stream(event -> { });
        chatFacade.prepareTurn(chat.id(), "m3").stream(event -> { });

        ArgumentCaptor<AssistantRequest> requests = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistantClient, times(3)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().getLast().history()).containsExactly(
                new HistoryMessage(HistoryRole.USER, "m1"),
                new HistoryMessage(HistoryRole.ASSISTANT, "ok"),
                new HistoryMessage(HistoryRole.USER, "m2"),
                new HistoryMessage(HistoryRole.ASSISTANT, "ok"));
    }
}
