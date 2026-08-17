package com.my.custom.claudepersonalassistant.assistant.config;

/**
 * Observation names and key names emitted by the {@code assistant} module.
 *
 * <p>These are spans, not meters — {@link AssistantMetrics} covers the timers and counters. They
 * exist because a Micrometer {@code Timer} records how long a turn took but never shows what the
 * turn was made of: the trace of one send-to-answer round trip is otherwise a single opaque model
 * call. One child span per content block, plus one per tool result, is what makes the text,
 * tool-use and tool-result sections of an answer distinguishable in Tempo.
 *
 * <p>The key names follow the OpenTelemetry GenAI semantic conventions' {@code gen_ai.*} prefix so
 * the spans sit alongside the ones Spring AI already emits ({@code spring.ai.chat.client},
 * {@code chat gpt-…}, {@code execute_tool …}) rather than inventing a parallel vocabulary.
 */
public final class AssistantObservations {

    /** One observation per Anthropic content block of a streamed answer. */
    public static final String CONTENT_BLOCK = "gen_ai.content.block";

    /** One observation per tool call, taken at the boundary where the result exists. */
    public static final String TOOL_RESULT = "gen_ai.tool.result";

    /** Span name of a tool-result observation; block observations derive theirs from the type. */
    public static final String CONTEXTUAL_NAME_TOOL_RESULT = "block tool_result";

    public static final String KEY_BLOCK_TYPE = "gen_ai.content.block.type";
    public static final String KEY_SYSTEM = "gen_ai.system";
    public static final String KEY_OUTCOME = "outcome";

    public static final String KEY_BLOCK_INDEX = "gen_ai.content.block.index";
    public static final String KEY_BLOCK_CHUNKS = "gen_ai.content.block.chunks";
    public static final String KEY_BLOCK_CHARS = "gen_ai.content.block.chars";

    public static final String KEY_TOOL_NAME = "gen_ai.tool.name";
    public static final String KEY_TOOL_RESULT_CHARS = "gen_ai.tool.result.chars";

    public static final String SYSTEM_ANTHROPIC = "anthropic";

    /**
     * The tool result is not a {@code BlockType}: no generation Spring AI hands back ever carries
     * one, so it is named here as a literal and attached at the tool boundary instead.
     */
    public static final String BLOCK_TYPE_TOOL_RESULT = "tool_result";

    private AssistantObservations() {
    }
}
