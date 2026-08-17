package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.assistant.api.VisionClient;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantMetrics;
import com.my.custom.claudepersonalassistant.assistant.dto.ImagePayload;
import com.my.custom.claudepersonalassistant.assistant.dto.VisionRequest;
import com.my.custom.claudepersonalassistant.assistant.error.AnthropicErrorClassifier;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;

/**
 * {@link VisionClient} over the same {@link ChatClient} bean the conversational path uses, called
 * with {@code call()} rather than {@code stream()}.
 *
 * <p>Three details are load-bearing and easy to undo by tidying:
 *
 * <ul>
 * <li><strong>The prefill must be the last message, and it must go through {@code messages(...)}
 * — never {@code user(...)}.</strong> {@code DefaultChatClient} renders system first, then
 * {@code messages(...)}, then whatever {@code user(...)} added, so a {@code user(...)} call here
 * would land <em>after</em> the prefill and stop it being one.</li>
 * <li><strong>{@code system(...)} is called explicitly</strong> to displace the
 * {@code defaultSystem} on the shared bean. Without it the model is told to refuse anything it
 * cannot source-check, and reading a photograph is exactly that.</li>
 * <li><strong>{@code options(...)} takes the builder, not the built options.</strong> That is the
 * only overload {@code ChatClientRequestSpec} declares; passing {@code build()} does not
 * compile.</li>
 * </ul>
 *
 * <p>No {@code AssistantErrorEvent} is published here. That event carries a conversation id and
 * feeds {@code assistant.stream.errors}, and a failed extraction is already counted by the caller —
 * an MCP tool invocation records {@code mcp.tool{outcome=failure}} either way. Publishing would
 * double-count it against a null conversation.
 */
@Component
@RequiredArgsConstructor
class ChatClientVision implements VisionClient {

    private final ChatClient chatClient;
    private final AnthropicErrorClassifier errorClassifier;
    private final MeterRegistry meterRegistry;

    @Override
    public String extract(VisionRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = AssistantMetrics.OUTCOME_SUCCESS;
        try {
            return chatClient.prompt()
                    .system(request.systemPrompt())
                    .messages(toMessages(request))
                    .options(AnthropicChatOptions.builder()
                            .maxTokens(request.maxTokens())
                            .stopSequences(request.stopSequences()))
                    .call()
                    .content();
        } catch (RuntimeException error) {
            outcome = AssistantMetrics.OUTCOME_FAILURE;
            throw new AssistantException(errorClassifier.classify(error), error);
        } finally {
            sample.stop(Timer.builder(AssistantMetrics.MODULE_OPERATION)
                    .tag(AssistantMetrics.TAG_MODULE, AssistantMetrics.MODULE)
                    .tag(AssistantMetrics.TAG_OPERATION, AssistantMetrics.OPERATION_VISION)
                    .tag(AssistantMetrics.TAG_OUTCOME, outcome)
                    .register(meterRegistry));
        }
    }

    private List<Message> toMessages(VisionRequest request) {
        List<Message> messages = new ArrayList<>(2);
        messages.add(UserMessage.builder()
                .text(request.userPrompt())
                .media(toMedia(request.image()))
                .build());
        // Blank means "no prefill". An assistant turn with empty content is not a no-op — Anthropic
        // rejects it — so the turn has to be absent rather than empty.
        if (StringUtils.hasText(request.assistantPrefill())) {
            // Stripped, not passed through: Anthropic rejects a final assistant turn ending in
            // whitespace with "final assistant content cannot end with trailing whitespace", a flat
            // 400 that says nothing about prefills. No caller can ever want that trailing newline —
            // it is rejected in every case — so removing it here is strictly better than letting
            // each one discover the error separately. The model writes the newline itself anyway.
            messages.add(new AssistantMessage(request.assistantPrefill().stripTrailing()));
        }
        return messages;
    }

    private Media toMedia(ImagePayload image) {
        return Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(image.mediaType()))
                .data(image.data())
                .build();
    }
}
