package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.AssistantErrorEvent;
import com.my.custom.claudepersonalassistant.assistant.AssistantException;
import com.my.custom.claudepersonalassistant.assistant.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.ClassifiedError;
import com.my.custom.claudepersonalassistant.assistant.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.error.AnthropicErrorClassifier;
import com.my.custom.claudepersonalassistant.assistant.error.AssistantErrorPublisher;
import com.my.custom.claudepersonalassistant.assistant.logging.BlockType;
import com.my.custom.claudepersonalassistant.assistant.logging.ContentBlockLogger;

/**
 * {@link AssistantClient} backed by Spring AI's {@link ChatClient}: replays the windowed
 * history, streams the new turn, logs content blocks, and maps failures to classified
 * {@link AssistantException}s while publishing {@link AssistantErrorEvent}s.
 *
 * <p>{@code Flux} only appears on the two lines below where Spring AI hands one back — the
 * app runs on plain Servlet MVC with virtual threads ({@code spring.threads.virtual.enabled}),
 * not WebFlux, so every chunk is consumed synchronously and pushed straight to {@code onDelta}
 * rather than re-exposed as a reactive type.
 */
@Component
@RequiredArgsConstructor
class ChatClientAssistant implements AssistantClient {

    private final ChatClient chatClient;
    private final AnthropicErrorClassifier errorClassifier;
    private final AssistantErrorPublisher errorPublisher;
    private final ContentBlockLogger contentBlockLogger;

    @Override
    public void stream(AssistantRequest request, Consumer<String> onDelta) {
        ContentBlockLogger.StreamSession session = contentBlockLogger.newSession(request.conversationId());
        Flux<ChatResponse> responses = chatClient.prompt()
                .messages(toMessages(request.history()))
                .user(request.userText())
                .stream()
                .chatResponse();
        // try-with-resources: a client disconnect throws up through onDelta and out of
        // forEach, closing this Stream, which cancels the upstream Reactor subscription and in
        // turn the Anthropic HTTP stream (Flux#toStream() wires Stream#close() to cancellation).
        try (Stream<ChatResponse> chunks = responses.toStream()) {
            chunks.forEach(response -> {
                session.onChunk(response);
                textDeltas(response).forEach(onDelta);
            });
            session.onComplete();
        } catch (RuntimeException error) {
            session.onError(error);
            AssistantException mapped = new AssistantException(errorClassifier.classify(error), error);
            errorPublisher.publish(toEvent(request.conversationId(), mapped));
            throw mapped;
        }
    }

    private List<Message> toMessages(List<HistoryMessage> history) {
        return history.stream()
                .<Message>map(message -> switch (message.role()) {
                    case USER -> new UserMessage(message.text());
                    case ASSISTANT -> new AssistantMessage(message.text());
                })
                .toList();
    }

    private List<String> textDeltas(ChatResponse response) {
        return response.getResults().stream()
                .filter(generation -> BlockType.fromMetadata(generation.getOutput().getMetadata()) == BlockType.TEXT)
                .map(generation -> generation.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty())
                .toList();
    }

    private AssistantErrorEvent toEvent(Long conversationId, AssistantException exception) {
        ClassifiedError error = exception.error();
        return new AssistantErrorEvent(conversationId, error.classification(), error.statusCode(), error.errorType(),
                error.message());
    }
}
