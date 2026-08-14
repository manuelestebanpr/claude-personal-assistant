package com.my.custom.claudepersonalassistant.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.ClassifiedError;
import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryRole;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;
import com.my.custom.claudepersonalassistant.chat.dto.ToolParameterDto;
import com.my.custom.claudepersonalassistant.chat.event.ChatCreatedEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatDeletedEvent;
import com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException;
import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ApplicationModuleTest
class ChatModuleIntegrationTests {

    @MockitoBean
    private AssistantClient assistantClient;

    @MockitoBean
    private McpToolGateway toolGateway;

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

        scenario.stimulate(() -> chatFacade.deleteConversation(created.id()))
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

    @Test
    void listsToolsTranslatedIntoTheChatModuleSOwnView() {
        given(toolGateway.listTools()).willReturn(List.of(new ToolDescriptor("local", "Local",
                "get_current_hour", "Current hour", "Returns the time.",
                Map.of("type", "object", "additionalProperties", false))));

        assertThat(chatFacade.listTools()).containsExactly(
                new ToolDto("local", "Local","get_current_hour", "Current hour", "Returns the time.", true, List.of()));
    }

    /**
     * The palette can only run a tool it can collect arguments for, so the fields have to survive
     * the translation out of the MCP schema — in the order the server declared them.
     */
    @Test
    void carriesEachToolSArgumentsThroughIntoTheViewModel() {
        given(toolGateway.listTools()).willReturn(List.of(new ToolDescriptor("local", "Local",
                "gmail_search_messages", "Search Gmail", "Searches Gmail.",
                Map.of("type", "object",
                        "properties", new java.util.LinkedHashMap<>(Map.of(
                                "query", Map.of("type", "string", "description", "Gmail query."))),
                        "required", List.of("query"),
                        "additionalProperties", false))));

        ToolDto tool = chatFacade.listTools().getFirst();

        assertThat(tool.runnableAsIs()).isFalse();
        assertThat(tool.parameters()).containsExactly(
                new ToolParameterDto("query", "Gmail query.", "string", true));
    }

    @Test
    void fallsBackToTheToolNameWhenTheServerGivesNoTitle() {
        given(toolGateway.listTools()).willReturn(List.of(
                new ToolDescriptor("local", "Local","get_current_hour", null, "Returns the time.", Map.of())));

        assertThat(chatFacade.listTools()).extracting(ToolDto::title).containsExactly("get_current_hour");
    }

    /** Losing the tool catalogue must not take the chat down with it. */
    @Test
    void reportsNoToolsWhenTheMcpServerIsUnreachable() {
        given(toolGateway.listTools()).willThrow(new McpClientException("connection refused"));

        assertThat(chatFacade.listTools()).isEmpty();
    }

    @Test
    void executingAToolPersistsItsOutputAndFeedsTheNextTurn() {
        given(toolGateway.callTool(ToolInvocation.of("local", "get_current_hour")))
                .willReturn(ToolResult.ok("The current time is 21:07 (Europe/Madrid)."));
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("It is 21:07.");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        ChatMessageDto recorded = chatFacade.executeTool(chat.id(), "local", "get_current_hour", Map.of());

        assertThat(recorded.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(recorded.content()).isEqualTo("The current time is 21:07 (Europe/Madrid).");
        // Survives a reload...
        assertThat(chatFacade.openConversation(chat.id()).messages())
                .extracting(ChatMessageDto::role, ChatMessageDto::content)
                .containsExactly(tuple(MessageRole.ASSISTANT, "The current time is 21:07 (Europe/Madrid)."));

        // ...and becomes model context, which is what lets the model answer a question it cannot
        // answer on its own.
        chatFacade.prepareTurn(chat.id(), "What time is it?").stream(event -> { });
        ArgumentCaptor<AssistantRequest> requests = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistantClient).stream(requests.capture(), any());
        assertThat(requests.getValue().history()).containsExactly(
                new HistoryMessage(HistoryRole.ASSISTANT, "The current time is 21:07 (Europe/Madrid)."));
    }

    @Test
    void offersTheModelTheCurrentToolCatalogueEachTurn() {
        given(toolGateway.listTools()).willReturn(List.of(new ToolDescriptor("local", "Local",
                "get_current_hour", "Current hour", "Returns the time.",
                Map.of("type", "object", "additionalProperties", false))));
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("ok");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        chatFacade.prepareTurn(chat.id(), "What time is it?").stream(event -> { });

        ArgumentCaptor<AssistantRequest> requests = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistantClient).stream(requests.capture(), any());
        assertThat(requests.getValue().tools()).containsExactly(new ToolSpecification(
                "get_current_hour", "Returns the time.",
                Map.of("type", "object", "additionalProperties", false)));
    }

    /** Losing the tool catalogue must not take the whole turn down with it either. */
    @Test
    void offersNoToolsToTheModelWhenTheMcpServerIsUnreachable() {
        given(toolGateway.listTools()).willThrow(new McpClientException("connection refused"));
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("ok");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        chatFacade.prepareTurn(chat.id(), "Hi").stream(event -> { });

        ArgumentCaptor<AssistantRequest> requests = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistantClient).stream(requests.capture(), any());
        assertThat(requests.getValue().tools()).isEmpty();
    }

    @Test
    void executingAToolOnAMissingChatFails() {
        assertThatThrownBy(() -> chatFacade.executeTool(4242L, "local", "get_current_hour", Map.of()))
                .isInstanceOf(ChatNotFoundException.class);
    }
}
