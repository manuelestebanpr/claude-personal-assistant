package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.List;

import com.anthropic.errors.RateLimitException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import com.my.custom.claudepersonalassistant.assistant.config.AssistantConstants;
import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.assistant.dto.ImagePayload;
import com.my.custom.claudepersonalassistant.assistant.dto.VisionRequest;
import com.my.custom.claudepersonalassistant.assistant.error.AnthropicErrorClassifier;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pins the three things this call needs Spring AI to carry that no other call in the application
 * asks for: an image, an assistant <em>prefill</em>, and stop sequences.
 *
 * <p>What the assertions here guarantee is the shape of the {@link Prompt}, because that is this
 * module's half of the contract. The other half — that {@code AnthropicChatModel} turns that shape
 * into the right wire request — was read out of its bytecode rather than guessed:
 * {@code createRequest} calls {@code UserMessage.getMedia()} into
 * {@code addUserMessageOfBlockParams}, maps {@code MessageType.ASSISTANT} through
 * {@code addAssistantMessage(String)} (a trailing assistant turn <em>is</em> a prefill on the
 * Anthropic API), and copies {@code AnthropicChatOptions.getStopSequences()} straight onto
 * {@code MessageCreateParams.Builder.stopSequences}.
 *
 * <p>The prefill matters because it is model-gated: Anthropic rejects it with a 400 on Opus and
 * Sonnet 4.6 and later, and accepts it on Haiku 4.5 — which is what
 * {@code spring.ai.anthropic.chat.model} is set to. Moving this application to a newer model breaks
 * the receipt parser, not this test, so the failure would appear at runtime.
 */
class ChatClientVisionTest {

    private static final String IMAGE_TYPE = "image/jpeg";
    private static final byte[] IMAGE_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};
    private static final String SYSTEM = "Read receipts and answer with JSON only.";
    private static final String PROMPT = "Read all the receipt.";
    private static final String PREFILL = "Here is the exact json for the groceries on the receipt```json";
    private static final String FENCE = "```";

    private final ChatModel chatModel = mock(ChatModel.class);
    private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();
    private ChatClientVision vision;

    @BeforeEach
    void createClient() {
        // Same reason as ChatClientAssistantTest: DefaultChatClientUtils mutates the model's options
        // unconditionally, and a plain ChatOptions here would not survive that.
        given(chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        ChatClient chatClient = ChatClient.builder(chatModel, observationRegistry, null, null)
                .defaultSystem(AssistantConstants.SYSTEM_PROMPT)
                .build();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        vision = new ChatClientVision(chatClient, new AnthropicErrorClassifier(), meterRegistry);
    }

    @Test
    void sendsSystemPromptImageAndPrefillInThatOrder() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("{}"));

        vision.extract(request(PREFILL));

        List<Message> instructions = capturedPrompt().getInstructions();
        assertThat(instructions).hasSize(3);
        assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(instructions.get(0).getText()).isEqualTo(SYSTEM);
        assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
        assertThat(instructions.get(1).getText()).isEqualTo(PROMPT);
        // Last, and an assistant turn: that is what makes it a prefill rather than an example.
        assertThat(instructions.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(instructions.get(2).getText()).isEqualTo(PREFILL);
    }

    @Test
    void overridesTheConversationalSystemPrompt() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("{}"));

        vision.extract(request(PREFILL));

        // The personal-assistant persona would tell the model to refuse without a checkable source,
        // which is exactly wrong for reading a photograph.
        assertThat(capturedPrompt().getInstructions().getFirst().getText())
                .isEqualTo(SYSTEM)
                .isNotEqualTo(AssistantConstants.SYSTEM_PROMPT);
    }

    @Test
    void attachesTheImageToTheUserMessage() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("{}"));

        vision.extract(request(PREFILL));

        UserMessage user = (UserMessage) capturedPrompt().getInstructions().get(1);
        assertThat(user.getMedia()).hasSize(1);
        Media media = user.getMedia().getFirst();
        assertThat(media.getMimeType().toString()).isEqualTo(IMAGE_TYPE);
        assertThat(media.getDataAsByteArray()).isEqualTo(IMAGE_BYTES);
    }

    @Test
    void putsStopSequencesAndMaxTokensOnTheOptions() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("{}"));

        vision.extract(request(PREFILL));

        ChatOptions options = capturedPrompt().getOptions();
        assertThat(options.getStopSequences()).containsExactly(FENCE);
        // Not the global spring.ai.anthropic.chat.max-tokens: that is a chat default, and a receipt
        // with thirty lines needs more room than a chat reply.
        assertThat(options.getMaxTokens()).isEqualTo(2048);
    }

    /**
     * Anthropic answers a prefill ending in whitespace with a bare
     * {@code 400 invalid_request_error} — "final assistant content cannot end with trailing
     * whitespace" — which mentions neither the prefill nor the fix. Since no caller can want that
     * whitespace, it is removed here rather than left for each one to trip over.
     */
    @Test
    void stripsTrailingWhitespaceFromThePrefill() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("{}"));

        vision.extract(request("Here is the json```json\n"));

        assertThat(capturedPrompt().getInstructions().get(2).getText())
                .isEqualTo("Here is the json```json");
    }

    @Test
    void omitsThePrefillTurnWhenNoneIsAskedFor() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("plain answer"));

        vision.extract(request(null));

        // A trailing empty assistant turn is a 400 from Anthropic, not a no-op.
        assertThat(capturedPrompt().getInstructions()).hasSize(2);
        assertThat(capturedPrompt().getInstructions().get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void returnsTheModelText() {
        given(chatModel.call(any(Prompt.class))).willReturn(answer("{\"items\":[]}"));

        assertThat(vision.extract(request(PREFILL))).isEqualTo("{\"items\":[]}");
    }

    @Test
    void mapsFailuresToAClassifiedAssistantException() {
        RateLimitException rateLimit = mock(RateLimitException.class);
        given(rateLimit.statusCode()).willReturn(429);
        given(rateLimit.getMessage()).willReturn("rate limited");
        given(chatModel.call(any(Prompt.class))).willThrow(rateLimit);

        Throwable thrown = catchThrowable(() -> vision.extract(request(PREFILL)));

        assertThat(thrown).isInstanceOf(AssistantException.class);
        assertThat(((AssistantException) thrown).classification()).isEqualTo(ErrorClassification.RETRYABLE);
    }

    private VisionRequest request(String prefill) {
        return new VisionRequest(SYSTEM, PROMPT, new ImagePayload(IMAGE_TYPE, IMAGE_BYTES),
                prefill, List.of(FENCE), 2048);
    }

    private Prompt capturedPrompt() {
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompts.capture());
        return prompts.getValue();
    }

    private ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
