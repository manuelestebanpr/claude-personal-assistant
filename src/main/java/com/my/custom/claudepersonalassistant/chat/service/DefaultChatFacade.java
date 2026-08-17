package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryRole;
import com.my.custom.claudepersonalassistant.assistant.dto.ImagePayload;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.api.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.config.ChatMetrics;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;
import com.my.custom.claudepersonalassistant.chat.dto.ImageUpload;
import com.my.custom.claudepersonalassistant.chat.dto.McpServerDto;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;
import com.my.custom.claudepersonalassistant.chat.dto.ToolParameterDto;
import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolInvocation;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;

/**
 * Orchestrates a chat turn: controller → this facade → services → repositories.
 * Builds model context manually from the persisted history (no Spring AI memory advisor —
 * the JPA message store is the single source of truth).
 *
 * <p>Tools are reached from here rather than from the controller, so the web layer only ever
 * knows about {@link ToolDto} and never that an MCP server exists.
 *
 * <p>Both halves of a turn carry an {@link Observation} as well as their timer. The two measure
 * different things and neither replaces the other: the timer feeds the {@code app.module.operation}
 * dashboard panels, while the observation is what puts a span on the trace, so the streaming half —
 * which runs long after the controller has returned — stops being a gap between the servlet span
 * and Spring AI's.
 */
@Service
@RequiredArgsConstructor
class DefaultChatFacade implements ChatFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatFacade.class);

    private static final String NO_TOOL_CATALOGUE =
            "No MCP server could be reached; the turn continues with no tools offered";

    private static final String KEY_ERROR = "error";
    private static final String KEY_DETAIL = "detail";

    private static final int MAX_DETAIL_CHARACTERS = 512;
    private static final String DETAIL_ELLIPSIS = "…";

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final AssistantClient assistantClient;
    private final McpToolGateway toolGateway;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    @Override
    public List<ConversationDto> listConversations() {
        return timed(ChatMetrics.OPERATION_LIST_CONVERSATIONS, conversationService::list);
    }

    @Override
    public ConversationDto createConversation() {
        return timed(ChatMetrics.OPERATION_CREATE_CONVERSATION, conversationService::create);
    }

    @Override
    public ConversationView openConversation(Long chatId) {
        return timed(ChatMetrics.OPERATION_OPEN_CONVERSATION, () -> {
            ConversationDto conversation = conversationService.get(chatId);
            return new ConversationView(conversation, messageService.history(chatId));
        });
    }

    @Override
    public void deleteConversation(Long chatId) {
        timed(ChatMetrics.OPERATION_DELETE_CONVERSATION, () -> {
            conversationService.delete(chatId);
            return null;
        });
    }

    @Override
    public ChatTurn prepareTurn(Long chatId, String userText, List<ImageUpload> images) {
        // Only the synchronous half is timed here; the assistant call is timed separately as
        // streamTurn, because mixing a database write with a model round-trip in one timer would
        // make both unreadable.
        Observation observation = turnObservation(ChatMetrics.OBSERVATION_PREPARE_TURN,
                ChatMetrics.OBSERVATION_PREPARE_TURN_CONTEXTUAL_NAME, chatId);
        AssistantRequest request = observation.observe(() -> {
            String outcome = ChatMetrics.OUTCOME_FAILURE;
            try {
                AssistantRequest prepared = timed(ChatMetrics.OPERATION_PREPARE_TURN, () -> {
                    conversationService.get(chatId); // 404 before the response body starts
                    List<ChatMessageDto> window = messageService.contextWindow(chatId); // read BEFORE saving
                    ChatMessageDto saved = messageService.append(chatId, MessageRole.USER, userText, images);
                    if (window.isEmpty()) {
                        conversationService.applyDerivedTitle(chatId, titleSourceFor(saved));
                    }
                    List<ToolSpecification> tools =
                            availableTools().stream().map(this::toSpecification).toList();
                    observation.highCardinalityKeyValue(ChatMetrics.KEY_CONTEXT_WINDOW_MESSAGES,
                                    String.valueOf(window.size()))
                            .highCardinalityKeyValue(ChatMetrics.KEY_TOOLS_OFFERED,
                                    String.valueOf(tools.size()))
                            .highCardinalityKeyValue(ChatMetrics.KEY_IMAGES_ATTACHED,
                                    String.valueOf(saved.attachments().size()));
                    // The model gets the text plus a note of each image's id; storage kept the text
                    // the user actually typed.
                    return new AssistantRequest(chatId, toHistory(window),
                            AttachmentNotes.annotate(userText, saved.attachments()),
                            toImagePayloads(images), tools);
                });
                outcome = ChatMetrics.OUTCOME_SUCCESS;
                return prepared;
            } finally {
                observation.lowCardinalityKeyValue(ChatMetrics.TAG_OUTCOME, outcome);
            }
        });
        return sink -> streamAnswer(chatId, request, sink);
    }

    @Override
    public List<McpServerDto> listServers() {
        return timed(ChatMetrics.OPERATION_LIST_SERVERS, () -> toolGateway.listServers().stream()
                .map(server -> new McpServerDto(server.id(), server.name(), server.url(),
                        server.protocol(), server.local(), server.reachable(), server.toolCount(),
                        server.detail()))
                .toList());
    }

    @Override
    public List<ToolDto> listTools() {
        return timed(ChatMetrics.OPERATION_LIST_TOOLS,
                () -> availableTools().stream().map(this::toDto).toList());
    }

    @Override
    public ChatMessageDto executeTool(Long chatId, String serverId, String toolName,
            Map<String, Object> arguments) {
        return timed(ChatMetrics.OPERATION_EXECUTE_TOOL, () -> {
            conversationService.get(chatId); // 404 for a missing chat, before running anything
            ToolResult result = toolGateway.callTool(new ToolInvocation(serverId, toolName, arguments));
            // Persisted as an assistant message so a reload matches the screen, and so the next
            // turn replays the answer as model context — which is what lets the model finally
            // answer questions it cannot answer on its own.
            return messageService.append(chatId, MessageRole.ASSISTANT, result.text());
        });
    }

    /**
     * Times one facade operation and tags the outcome. Explicit rather than {@code @Timed}, which
     * would need an AOP starter this project does not carry.
     */
    private <T> T timed(String operation, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = ChatMetrics.OUTCOME_SUCCESS;
        try {
            return work.get();
        } catch (RuntimeException failure) {
            outcome = ChatMetrics.OUTCOME_FAILURE;
            throw failure;
        } finally {
            sample.stop(Timer.builder(ChatMetrics.MODULE_OPERATION)
                    .tag(ChatMetrics.TAG_MODULE, ChatMetrics.MODULE)
                    .tag(ChatMetrics.TAG_OPERATION, operation)
                    .tag(ChatMetrics.TAG_OUTCOME, outcome)
                    .register(meterRegistry));
        }
    }

    /**
     * An unstarted observation for one half of a turn — unstarted because the streaming half has to
     * open its own scope and hold it across the sink callbacks. The chat id is high cardinality
     * rather than a tag: it is unbounded, and tagging it would mint a meter per conversation.
     */
    private Observation turnObservation(String name, String contextualName, Long chatId) {
        return Observation.createNotStarted(name, observationRegistry)
                .contextualName(contextualName)
                .lowCardinalityKeyValue(ChatMetrics.TAG_MODULE, ChatMetrics.MODULE)
                .highCardinalityKeyValue(ChatMetrics.KEY_CHAT_ID, String.valueOf(chatId));
    }

    private ToolDto toDto(ToolDescriptor descriptor) {
        String title = descriptor.title() == null || descriptor.title().isBlank()
                ? descriptor.name()
                : descriptor.title();
        return new ToolDto(descriptor.serverId(), descriptor.serverName(), descriptor.name(), title,
                descriptor.description(), descriptor.takesNoArguments(), toParameterDtos(descriptor));
    }

    private List<ToolParameterDto> toParameterDtos(ToolDescriptor descriptor) {
        return descriptor.parameters().stream()
                .map(parameter -> new ToolParameterDto(parameter.name(), parameter.description(),
                        parameter.type(), parameter.required()))
                .toList();
    }

    private ToolSpecification toSpecification(ToolDescriptor descriptor) {
        return new ToolSpecification(descriptor.name(), descriptor.description(), descriptor.inputSchema());
    }

    /**
     * Tools currently on offer across every connected server, or none when none can be reached —
     * an MCP outage is an enhancement lost, not a reason to fail the page or the turn.
     */
    private List<ToolDescriptor> availableTools() {
        try {
            return toolGateway.listTools();
        } catch (McpClientException unreachable) {
            // A constant body, and one that names this condition rather than the gateway's: with
            // service_name the only Loki index label, two identical bodies from two layers of the
            // same call chain cannot be told apart in a query.
            log.atWarn()
                    .addKeyValue(KEY_ERROR, unreachable.getClass().getSimpleName())
                    .addKeyValue(KEY_DETAIL, truncated(unreachable))
                    .log(NO_TOOL_CATALOGUE);
            return List.of();
        }
    }

    /**
     * Caps the failure text before it becomes a log key-value.
     *
     * <p>A key-value leaves this application as an OTLP log-record attribute and reaches Loki as
     * structured metadata, and Loki answers an oversized entry with a 400 for the <em>whole</em>
     * record rather than trimming the field — so an unbounded detail costs the very line that
     * reports the outage. The text is unbounded in practice: a remote MCP server rejecting a token
     * still returns its full tool catalogue, and that body travels inside the client exception's
     * message. A few hundred characters still identify the transport failure.
     */
    private String truncated(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.length() <= MAX_DETAIL_CHARACTERS) {
            return message;
        }
        return message.substring(0, MAX_DETAIL_CHARACTERS) + DETAIL_ELLIPSIS;
    }

    /**
     * Drives the assistant call and the sink, then persists whatever was produced.
     *
     * <p>The observation is started and scoped by hand rather than through {@code observe(...)}
     * because the scope has to stay open across the sink callbacks — the assistant module opens its
     * own spans from inside them, and they belong under this one. {@code stop()} runs after
     * {@code persistAnswer}, so the write that makes a reload match the screen is inside the span
     * rather than after it.
     */
    private void streamAnswer(Long chatId, AssistantRequest request, Consumer<StreamEvent> sink) {
        StringBuilder answer = new StringBuilder();
        AtomicInteger streamedEvents = new AtomicInteger();
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = ChatMetrics.OUTCOME_SUCCESS;
        Observation observation = turnObservation(ChatMetrics.OBSERVATION_STREAM_TURN,
                ChatMetrics.OBSERVATION_STREAM_TURN_CONTEXTUAL_NAME, chatId)
                .lowCardinalityKeyValue(ChatMetrics.TAG_ERROR_CLASSIFICATION, ChatMetrics.CLASSIFICATION_NONE)
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            // Two nested blocks rather than one, because a catch never sees what its own finally
            // throws. Only an AssistantException is part of the stream protocol; the terminal DONE
            // line refused by a client that has closed the tab, and a failing persistAnswer, both
            // arrive as something else and would otherwise reach the outer finally with the outcome
            // still reading success — a disconnected turn counted as a delivered one, which is the
            // signal this observation exists to give.
            try {
                try {
                    assistantClient.stream(request, delta -> {
                        answer.append(delta);
                        emit(sink, streamedEvents, StreamEvent.delta(delta));
                    });
                    emit(sink, streamedEvents, StreamEvent.done());
                } catch (AssistantException exception) {
                    outcome = ChatMetrics.OUTCOME_FAILURE;
                    observation.lowCardinalityKeyValue(ChatMetrics.TAG_ERROR_CLASSIFICATION,
                            exception.classification().name());
                    observation.error(exception);
                    emit(sink, streamedEvents,
                            StreamEvent.error(exception.classification(), exception.getMessage()));
                } finally {
                    // persist partial (error/cancel) or full (complete) answer, so a reload always
                    // matches what the user saw
                    persistAnswer(chatId, answer);
                }
            } catch (RuntimeException failure) {
                outcome = ChatMetrics.OUTCOME_FAILURE;
                observation.error(failure);
                throw failure;
            }
        } finally {
            observation.lowCardinalityKeyValue(ChatMetrics.TAG_OUTCOME, outcome)
                    .highCardinalityKeyValue(ChatMetrics.KEY_ANSWER_CHARACTERS,
                            String.valueOf(answer.length()))
                    .highCardinalityKeyValue(ChatMetrics.KEY_ANSWER_PERSISTED,
                            String.valueOf(!answer.isEmpty()))
                    .highCardinalityKeyValue(ChatMetrics.KEY_STREAM_EVENTS,
                            String.valueOf(streamedEvents.get()));
            observation.stop();
            sample.stop(Timer.builder(ChatMetrics.MODULE_OPERATION)
                    .tag(ChatMetrics.TAG_MODULE, ChatMetrics.MODULE)
                    .tag(ChatMetrics.TAG_OPERATION, ChatMetrics.OPERATION_STREAM_TURN)
                    .tag(ChatMetrics.TAG_OUTCOME, outcome)
                    .register(meterRegistry));
        }
    }

    /** Counts what the turn actually put on the wire, including the terminal DONE or ERROR line. */
    private void emit(Consumer<StreamEvent> sink, AtomicInteger streamedEvents, StreamEvent event) {
        streamedEvents.incrementAndGet();
        sink.accept(event);
    }

    private void persistAnswer(Long chatId, StringBuilder answer) {
        if (!answer.isEmpty()) {
            messageService.append(chatId, MessageRole.ASSISTANT, answer.toString());
        }
    }

    /**
     * Replayed history carries the image <em>ids</em>, not the images: the bytes are gone from the
     * model's view after their own turn, but a follow-up question about "the receipt" still has a
     * handle a tool can be pointed at.
     */
    private List<HistoryMessage> toHistory(List<ChatMessageDto> window) {
        return window.stream()
                .map(message -> new HistoryMessage(toHistoryRole(message.role()),
                        AttachmentNotes.annotate(message.content(), message.attachments())))
                .toList();
    }

    private List<ImagePayload> toImagePayloads(List<ImageUpload> images) {
        return images == null
                ? List.of()
                : images.stream().map(image -> new ImagePayload(image.mediaType(), image.data())).toList();
    }

    /**
     * A chat opened with nothing but a photograph still needs a name. Falling back to the note gives
     * the sidebar something that says "there is an image here" rather than an empty row.
     */
    private String titleSourceFor(ChatMessageDto message) {
        return StringUtils.hasText(message.content())
                ? message.content()
                : AttachmentNotes.annotate(message.content(), message.attachments());
    }

    private HistoryRole toHistoryRole(MessageRole role) {
        return switch (role) {
            case USER -> HistoryRole.USER;
            case ASSISTANT -> HistoryRole.ASSISTANT;
        };
    }
}
