package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;
import java.util.function.Consumer;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.my.custom.claudepersonalassistant.assistant.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.AssistantException;
import com.my.custom.claudepersonalassistant.assistant.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.HistoryRole;
import com.my.custom.claudepersonalassistant.chat.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.ConversationView;
import com.my.custom.claudepersonalassistant.chat.MessageRole;
import com.my.custom.claudepersonalassistant.chat.StreamEvent;

/**
 * Orchestrates a chat turn: controller → this facade → services → repositories.
 * Builds model context manually from the persisted history (no Spring AI memory advisor —
 * the JPA message store is the single source of truth).
 */
@Service
@RequiredArgsConstructor
class DefaultChatFacade implements ChatFacade {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final AssistantClient assistantClient;

    @Override
    public List<ConversationDto> listConversations() {
        return conversationService.list();
    }

    @Override
    public ConversationDto createConversation() {
        return conversationService.create();
    }

    @Override
    public ConversationView openConversation(Long chatId) {
        ConversationDto conversation = conversationService.get(chatId);
        return new ConversationView(conversation, messageService.history(chatId));
    }

    @Override
    public void deleteConversation(Long chatId) {
        conversationService.delete(chatId);
    }

    @Override
    public ChatTurn prepareTurn(Long chatId, String userText) {
        conversationService.get(chatId); // 404 before the response body starts
        List<ChatMessageDto> window = messageService.contextWindow(chatId); // read BEFORE saving
        messageService.append(chatId, MessageRole.USER, userText);
        if (window.isEmpty()) {
            conversationService.applyDerivedTitle(chatId, userText);
        }

        AssistantRequest request = new AssistantRequest(chatId, toHistory(window), userText);
        return sink -> streamAnswer(chatId, request, sink);
    }

    private void streamAnswer(Long chatId, AssistantRequest request, Consumer<StreamEvent> sink) {
        StringBuilder answer = new StringBuilder();
        try {
            assistantClient.stream(request, delta -> {
                answer.append(delta);
                sink.accept(StreamEvent.delta(delta));
            });
            sink.accept(StreamEvent.done());
        } catch (AssistantException exception) {
            sink.accept(StreamEvent.error(exception.classification(), exception.getMessage()));
        } finally {
            // persist partial (error/cancel) or full (complete) answer, so a reload always
            // matches what the user saw
            persistAnswer(chatId, answer);
        }
    }

    private void persistAnswer(Long chatId, StringBuilder answer) {
        if (!answer.isEmpty()) {
            messageService.append(chatId, MessageRole.ASSISTANT, answer.toString());
        }
    }

    private List<HistoryMessage> toHistory(List<ChatMessageDto> window) {
        return window.stream()
                .map(message -> new HistoryMessage(toHistoryRole(message.role()), message.content()))
                .toList();
    }

    private HistoryRole toHistoryRole(MessageRole role) {
        return switch (role) {
            case USER -> HistoryRole.USER;
            case ASSISTANT -> HistoryRole.ASSISTANT;
        };
    }
}
