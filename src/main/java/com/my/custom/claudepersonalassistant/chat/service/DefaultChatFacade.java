package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryRole;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.api.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.config.ChatMetrics;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;
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
 */
@Service
@RequiredArgsConstructor
class DefaultChatFacade implements ChatFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatFacade.class);

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final AssistantClient assistantClient;
    private final McpToolGateway toolGateway;
    private final MeterRegistry meterRegistry;

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
    public ChatTurn prepareTurn(Long chatId, String userText) {
        // Only the synchronous half is timed here; the assistant call is timed separately as
        // streamTurn, because mixing a database write with a model round-trip in one timer would
        // make both unreadable.
        AssistantRequest request = timed(ChatMetrics.OPERATION_PREPARE_TURN, () -> {
            conversationService.get(chatId); // 404 before the response body starts
            List<ChatMessageDto> window = messageService.contextWindow(chatId); // read BEFORE saving
            messageService.append(chatId, MessageRole.USER, userText);
            if (window.isEmpty()) {
                conversationService.applyDerivedTitle(chatId, userText);
            }
            List<ToolSpecification> tools = availableTools().stream().map(this::toSpecification).toList();
            return new AssistantRequest(chatId, toHistory(window), userText, tools);
        });
        return sink -> streamAnswer(chatId, request, sink);
    }

    @Override
    public List<ToolDto> listTools() {
        return timed(ChatMetrics.OPERATION_LIST_TOOLS,
                () -> availableTools().stream().map(this::toDto).toList());
    }

    @Override
    public ChatMessageDto executeTool(Long chatId, String toolName, Map<String, Object> arguments) {
        return timed(ChatMetrics.OPERATION_EXECUTE_TOOL, () -> {
            conversationService.get(chatId); // 404 for a missing chat, before running anything
            ToolResult result = toolGateway.callTool(new ToolInvocation(toolName, arguments));
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

    private ToolDto toDto(ToolDescriptor descriptor) {
        String title = descriptor.title() == null || descriptor.title().isBlank()
                ? descriptor.name()
                : descriptor.title();
        return new ToolDto(descriptor.name(), title, descriptor.description(), descriptor.takesNoArguments());
    }

    private ToolSpecification toSpecification(ToolDescriptor descriptor) {
        return new ToolSpecification(descriptor.name(), descriptor.description(), descriptor.inputSchema());
    }

    /**
     * Tools currently on offer, or none when the server is unreachable — an MCP outage is an
     * enhancement lost, not a reason to fail the page or the turn.
     */
    private List<ToolDescriptor> availableTools() {
        try {
            return toolGateway.listTools();
        } catch (McpClientException unreachable) {
            log.atWarn()
                    .addKeyValue("error", unreachable.getClass().getSimpleName())
                    .log("Tool catalogue unavailable: {}", unreachable.getMessage());
            return List.of();
        }
    }

    private void streamAnswer(Long chatId, AssistantRequest request, Consumer<StreamEvent> sink) {
        StringBuilder answer = new StringBuilder();
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = ChatMetrics.OUTCOME_SUCCESS;
        try {
            assistantClient.stream(request, delta -> {
                answer.append(delta);
                sink.accept(StreamEvent.delta(delta));
            });
            sink.accept(StreamEvent.done());
        } catch (AssistantException exception) {
            outcome = ChatMetrics.OUTCOME_FAILURE;
            sink.accept(StreamEvent.error(exception.classification(), exception.getMessage()));
        } finally {
            // persist partial (error/cancel) or full (complete) answer, so a reload always
            // matches what the user saw
            persistAnswer(chatId, answer);
            sample.stop(Timer.builder(ChatMetrics.MODULE_OPERATION)
                    .tag(ChatMetrics.TAG_MODULE, ChatMetrics.MODULE)
                    .tag(ChatMetrics.TAG_OPERATION, ChatMetrics.OPERATION_STREAM_TURN)
                    .tag(ChatMetrics.TAG_OUTCOME, outcome)
                    .register(meterRegistry));
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
