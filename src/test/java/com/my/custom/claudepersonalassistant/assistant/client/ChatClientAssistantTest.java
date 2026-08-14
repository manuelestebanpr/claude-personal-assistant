package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.errors.RateLimitException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import tools.jackson.databind.json.JsonMapper;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantConstants;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryRole;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;
import com.my.custom.claudepersonalassistant.assistant.error.AnthropicErrorClassifier;
import com.my.custom.claudepersonalassistant.assistant.error.AssistantErrorPublisher;
import com.my.custom.claudepersonalassistant.assistant.event.AssistantErrorEvent;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;
import com.my.custom.claudepersonalassistant.assistant.logging.ContentBlockLogger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Builds a real {@link ChatClient} over a mocked {@link ChatModel} — the sanctioned pattern,
 * since Spring AI ships no mock support for {@code ChatClient} itself.
 */
class ChatClientAssistantTest {

    private static final String METADATA_KEY_THINKING = "thinking";
    private static final String METADATA_KEY_SIGNATURE = "signature";
    private static final String METADATA_KEY_REDACTED_DATA = "data";

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AssistantErrorPublisher errorPublisher = mock(AssistantErrorPublisher.class);
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private ChatClientAssistant assistant;

    @BeforeEach
    void createAssistant() {
        // DefaultChatClientUtils.toChatClientRequest mutates the model's options unconditionally.
        // Real providers (Anthropic included) return ToolCallingChatOptions from getOptions(), which
        // is what lets the tool-calling advisor attach our callbacks at all; a plain ChatOptions here
        // would silently make every tool-calling test below pass through with no tools offered.
        given(chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(AssistantConstants.SYSTEM_PROMPT)
                .build();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        assistant = new ChatClientAssistant(chatClient, new AnthropicErrorClassifier(), errorPublisher,
                new ContentBlockLogger(meterRegistry), meterRegistry, toolExecutor, JsonMapper.builder().build());
    }

    @Test
    void emitsOnlyTextDeltasInOrder() {
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(
                textChunk("Hel"),
                metadataChunk(METADATA_KEY_THINKING, "pondering"),
                metadataChunk(METADATA_KEY_SIGNATURE, "sig"),
                metadataChunk(METADATA_KEY_REDACTED_DATA, "opaque"),
                textChunk("lo"),
                finalChunk("end_turn")));

        List<String> deltas = new ArrayList<>();
        assistant.stream(request(1L), deltas::add);

        assertThat(deltas).containsExactly("Hel", "lo");
    }

    @Test
    void sendsSystemPromptHistoryAndUserTextInOrder() {
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(textChunk("ok")));
        AssistantRequest request = new AssistantRequest(7L,
                List.of(new HistoryMessage(HistoryRole.USER, "earlier question"),
                        new HistoryMessage(HistoryRole.ASSISTANT, "earlier answer")),
                "new question", List.of());

        assistant.stream(request, delta -> { });

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompts.capture());
        List<Message> instructions = prompts.getValue().getInstructions();
        assertThat(instructions).hasSize(4);
        assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(instructions.get(0).getText()).isEqualTo(AssistantConstants.SYSTEM_PROMPT);
        assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
        assertThat(instructions.get(1).getText()).isEqualTo("earlier question");
        assertThat(instructions.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(instructions.get(2).getText()).isEqualTo("earlier answer");
        assertThat(instructions.get(3)).isInstanceOf(UserMessage.class);
        assertThat(instructions.get(3).getText()).isEqualTo("new question");
    }

    @Test
    void mapsFailuresToClassifiedAssistantExceptionAndPublishesErrorEvent() {
        RateLimitException rateLimit = mock(RateLimitException.class);
        given(rateLimit.statusCode()).willReturn(429);
        given(rateLimit.getMessage()).willReturn("rate limited");
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.error(rateLimit));

        Throwable thrown = catchThrowable(() -> assistant.stream(request(9L), delta -> { }));

        assertThat(thrown).isInstanceOf(AssistantException.class);
        AssistantException assistantException = (AssistantException) thrown;
        assertThat(assistantException.classification()).isEqualTo(ErrorClassification.RETRYABLE);
        assertThat(assistantException.error().statusCode()).isEqualTo(429);

        ArgumentCaptor<AssistantErrorEvent> events = ArgumentCaptor.forClass(AssistantErrorEvent.class);
        verify(errorPublisher).publish(events.capture());
        assertThat(events.getValue().conversationId()).isEqualTo(9L);
        assertThat(events.getValue().classification()).isEqualTo(ErrorClassification.RETRYABLE);
        assertThat(events.getValue().statusCode()).isEqualTo(429);
    }

    @Test
    void offersTheRequestSToolsToTheModel() {
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(textChunk("ok")));
        ToolSpecification tool = new ToolSpecification("get_current_hour", "Returns the time.",
                Map.of("type", "object", "additionalProperties", false));
        AssistantRequest request = new AssistantRequest(1L, List.of(), "What time is it?", List.of(tool));

        assistant.stream(request, delta -> { });

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompts.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompts.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("get_current_hour");
    }

    @Test
    void executesAModelInitiatedToolCallAndStreamsTheFollowUpAnswer() {
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "get_current_hour", "{}");
        ChatResponse toolCallResponse = ChatResponse.builder()
                .generations(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build(),
                        ChatGenerationMetadata.builder().finishReason("tool_calls").build())))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(5, 5)).build())
                .build();
        given(chatModel.stream(any(Prompt.class)))
                .willReturn(Flux.just(toolCallResponse))
                .willReturn(Flux.just(textChunk("It is 21:07."), finalChunk("end_turn")));
        given(toolExecutor.execute("get_current_hour", Map.of())).willReturn(ToolExecutionResult.ok("21:07"));
        ToolSpecification tool = new ToolSpecification("get_current_hour", "Returns the time.", Map.of());
        AssistantRequest request = new AssistantRequest(1L, List.of(), "What time is it?", List.of(tool));

        List<String> deltas = new ArrayList<>();
        assistant.stream(request, deltas::add);

        assertThat(deltas).containsExactly("It is 21:07.");
        verify(toolExecutor).execute("get_current_hour", Map.of());
    }

    private AssistantRequest request(Long conversationId) {
        return new AssistantRequest(conversationId, List.of(), "hello", List.of());
    }

    private ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatResponse metadataChunk(String metadataKey, String value) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of(metadataKey, value))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse finalChunk(String finishReason) {
        Generation generation = new Generation(new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(10, 20)).build())
                .build();
    }
}
