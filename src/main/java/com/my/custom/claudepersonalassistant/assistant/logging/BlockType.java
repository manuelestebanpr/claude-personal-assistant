package com.my.custom.claudepersonalassistant.assistant.logging;

import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Anthropic content-block type of a streamed chunk.
 *
 * <p>Two discriminators, in this order, because Spring AI exposes the two kinds of block
 * differently:
 *
 * <ul>
 *   <li><b>Tool use is not metadata.</b> The only metadata keys {@code AnthropicChatModel} ever
 *       sets are {@code thinking}, {@code signature} and {@code data} — there is no
 *       {@code tool_use} key — so a tool call read through the metadata alone classifies as
 *       {@link #TEXT} and disappears from block accounting. {@link AssistantMessage#hasToolCalls()}
 *       is therefore checked first.</li>
 *   <li><b>A tool call arrives in exactly one chunk.</b> {@code convertStreamEventToChatResponse}
 *       returns {@code null} for {@code content_block_start}, the {@code input_json} deltas and
 *       {@code content_block_stop}, so nothing is emitted while the arguments accumulate; the
 *       completed calls surface only on the final {@code message_delta}, as
 *       {@code AssistantMessage.builder().content("").toolCalls(completed).build()}. A
 *       {@link #TOOL_USE} block is consequently one chunk wide.</li>
 * </ul>
 *
 * <p>There is deliberately no {@code TOOL_RESULT} member: the generation metadata that would
 * carry one ({@code toolId}/{@code toolName}, written by Spring AI's
 * {@code org.springframework.ai.model.tool.ToolExecutionResult#buildGenerations}) is only ever
 * produced when {@code ToolMetadata.returnDirect()} is {@code true}, and this module's
 * {@code ToolCallbackAdapter} inherits the default {@code returnDirect=false}. Such a generation
 * therefore never reaches this enum. The tool result is observed at the tool boundary instead,
 * where the arguments and the outcome actually exist.
 */
public enum BlockType {

    TEXT("text"),
    THINKING("thinking"),
    SIGNATURE("signature"),
    REDACTED("redacted"),
    TOOL_USE("tool_use");

    static final String METADATA_KEY_THINKING = "thinking";
    static final String METADATA_KEY_SIGNATURE = "signature";
    static final String METADATA_KEY_REDACTED_DATA = "data";

    private static final String CONTEXTUAL_NAME_PREFIX = "block ";

    private final String tag;

    BlockType(String tag) {
        this.tag = tag;
    }

    /**
     * Wire-level name of the block, used as an observation tag value rather than
     * {@link #name()} so the span carries Anthropic's own vocabulary.
     */
    public String tag() {
        return tag;
    }

    /** Span name of a block observation, e.g. {@code block tool_use}. */
    public String contextualName() {
        return CONTEXTUAL_NAME_PREFIX + tag;
    }

    public static BlockType of(AssistantMessage message) {
        if (message == null) {
            return TEXT;
        }
        if (message.hasToolCalls()) {
            return TOOL_USE;
        }
        return fromMetadata(message.getMetadata());
    }

    private static BlockType fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return TEXT;
        }
        if (metadata.containsKey(METADATA_KEY_SIGNATURE)) {
            return SIGNATURE;
        }
        if (metadata.containsKey(METADATA_KEY_THINKING)) {
            return THINKING;
        }
        if (metadata.containsKey(METADATA_KEY_REDACTED_DATA)) {
            return REDACTED;
        }
        return TEXT;
    }
}
