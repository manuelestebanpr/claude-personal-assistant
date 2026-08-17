package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import tools.jackson.databind.ObjectMapper;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantMetrics;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.ClassifiedError;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.dto.ImagePayload;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;
import com.my.custom.claudepersonalassistant.assistant.error.AnthropicErrorClassifier;
import com.my.custom.claudepersonalassistant.assistant.error.AssistantErrorPublisher;
import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;
import com.my.custom.claudepersonalassistant.assistant.logging.BlockType;
import com.my.custom.claudepersonalassistant.assistant.logging.ContentBlockLogger;
import com.my.custom.claudepersonalassistant.assistant.profile.AssistantProfile;
import com.my.custom.claudepersonalassistant.assistant.profile.AssistantProfiles;

/**
 * {@link AssistantClient} backed by Spring AI's {@link ChatClient}: replays the windowed
 * history, streams the new turn, logs content blocks, and maps failures to classified
 * {@link AssistantException}s while publishing {@link AssistantErrorEvent}s.
 *
 * <p>{@code Flux} only appears on the lines below where Spring AI hands one back, and
 * {@code reactor.util.context.Context} only to hand the caller's observation back the other way —
 * the app runs on plain Servlet MVC with virtual threads ({@code spring.threads.virtual.enabled}),
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
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private final AssistantProfiles assistantProfiles;

    @Override
    public void stream(AssistantRequest request, Consumer<String> onDelta) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = AssistantMetrics.OUTCOME_SUCCESS;
        ContentBlockLogger.StreamSession session = contentBlockLogger.newSession(request.conversationId());
        // Read once, here, because this is the only thread that reliably holds the caller's scope:
        // the tool callbacks are invoked later on a Reactor scheduler thread with nothing on the
        // thread local, so the parent has to travel with them rather than be looked up again.
        Observation caller = observationRegistry.getCurrentObservation();
        // The profile is what makes two assistants different calls over one client: its system
        // prompt displaces any default, its model rides the per-call options, and its allowlist
        // decides which of the caller's tools the model is offered at all.
        AssistantProfile profile = assistantProfiles.profile(request.assistantId());
        Flux<ChatResponse> responses = chatClient.prompt()
                .system(profile.systemPrompt())
                .messages(toMessages(request.history()))
                .user(userSpec -> withImages(userSpec, request))
                .tools(toToolCallbacks(allowedTools(request, profile), caller))
                .options(AnthropicChatOptions.builder().model(profile.model()))
                .stream()
                .chatResponse()
                .contextWrite(withCallerObservation(caller));
        // try-with-resources: a client disconnect throws up through onDelta and out of
        // forEach, closing this Stream, which cancels the upstream Reactor subscription and in
        // turn the Anthropic HTTP stream (Flux#toStream() wires Stream#close() to cancellation).
        // The session is closed in the finally block for the same reason: whatever ends the
        // stream, the block observation it left open must still be stopped.
        try (Stream<ChatResponse> chunks = responses.toStream()) {
            chunks.forEach(response -> {
                session.onChunk(response);
                textDeltas(response).forEach(onDelta);
            });
            session.onComplete();
        } catch (RuntimeException error) {
            outcome = AssistantMetrics.OUTCOME_FAILURE;
            session.onError(error);
            AssistantException mapped = new AssistantException(errorClassifier.classify(error), error);
            errorPublisher.publish(toEvent(request.conversationId(), mapped));
            throw mapped;
        } finally {
            session.close();
            sample.stop(Timer.builder(AssistantMetrics.MODULE_OPERATION)
                    .tag(AssistantMetrics.TAG_MODULE, AssistantMetrics.MODULE)
                    .tag(AssistantMetrics.TAG_OPERATION, AssistantMetrics.OPERATION_STREAM)
                    .tag(AssistantMetrics.TAG_OUTCOME, outcome)
                    .register(meterRegistry));
        }
    }

    /**
     * Hands the caller's observation — the chat module's turn observation — to Spring AI through
     * the Reactor context, which is the only channel it reads a parent from.
     *
     * <p>Both {@code DefaultChatClient} and {@code AnthropicChatModel} do the same thing
     * unconditionally: {@code observation.parentObservation(ctx.getOrDefault(
     * ObservationThreadLocalAccessor.KEY, null))}. Nothing populates that key here — this stream is
     * subscribed through {@code Flux#toStream()}, and automatic context propagation is off because
     * {@code Hooks.enableAutomaticContextPropagation()} is never called and no Reactor
     * auto-configuration is on this (non-WebFlux) classpath — so the lookup yields {@code null} and
     * the parent is explicitly cleared. The spans still come out nested because micrometer-tracing
     * falls back to {@code tracer.nextSpan()} over the ambient OpenTelemetry context, but the
     * Micrometer-level chain is broken, and anything reading parents from Micrometer rather than
     * from OpenTelemetry sees a root. Writing the key makes the link real instead of incidental.
     */
    private Function<Context, Context> withCallerObservation(Observation caller) {
        return caller == null
                ? Function.identity()
                : context -> context.put(ObservationThreadLocalAccessor.KEY, caller);
    }

    /**
     * The new turn: its text, and any images sent with it.
     *
     * <p>Only this turn carries images. Replayed history is text — see {@link AssistantRequest} for
     * why — so the media call belongs here and not in {@link #toMessages(List)}.
     */
    private void withImages(ChatClient.PromptUserSpec userSpec, AssistantRequest request) {
        userSpec.text(request.userText());
        if (!request.images().isEmpty()) {
            userSpec.media(request.images().stream().map(this::toMedia).toArray(Media[]::new));
        }
    }

    private Media toMedia(ImagePayload image) {
        return Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(image.mediaType()))
                .data(image.data())
                .build();
    }

    private List<Message> toMessages(List<HistoryMessage> history) {
        return history.stream()
                .<Message>map(message -> switch (message.role()) {
                    case USER -> new UserMessage(message.text());
                    case ASSISTANT -> new AssistantMessage(message.text());
                })
                .toList();
    }

    /**
     * Builds the callbacks eagerly, on the calling thread, and hands each the caller's observation.
     * Spring AI's {@code ToolCallingAdvisor} runs the tool-execution branch of a streamed turn on
     * {@code Schedulers.boundedElastic()}, and — for the same reason spelled out on
     * {@link #withCallerObservation(Observation)} — the Reactor context never becomes a thread
     * local there. A callback reading the registry at invocation time would therefore find nothing
     * and open its span as a root; carrying the parent keeps the tool call on the turn's trace.
     */
    private List<ToolCallback> toToolCallbacks(List<ToolSpecification> tools, Observation caller) {
        return tools.stream()
                .<ToolCallback>map(tool -> new ToolCallbackAdapter(tool, toolExecutor, objectMapper,
                        observationRegistry, caller))
                .toList();
    }

    private List<ToolSpecification> allowedTools(AssistantRequest request, AssistantProfile profile) {
        return request.tools().stream()
                .filter(tool -> profile.allowsTool(tool.serverId(), tool.name()))
                .toList();
    }

    private List<String> textDeltas(ChatResponse response) {
        return response.getResults().stream()
                .filter(generation -> BlockType.of(generation.getOutput()) == BlockType.TEXT)
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
