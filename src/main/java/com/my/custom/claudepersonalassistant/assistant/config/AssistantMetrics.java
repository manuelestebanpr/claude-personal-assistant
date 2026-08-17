package com.my.custom.claudepersonalassistant.assistant.config;

/**
 * Meter names and tag keys emitted by the {@code assistant} module.
 *
 * <p>A boundary timer is named {@code app.module.operation} and distinguishes its module with the
 * {@code module} tag, so one dashboard panel covers every module that has one. Only {@code
 * assistant} and {@code chat} do: {@code mcp} times {@code mcp.tool} instead and {@code audit}
 * declares no timer at all, so the {@code module} tag currently carries two values, not four. The
 * constants are duplicated per module rather than shared: a module that could import another
 * module's metric names would be depending on it.
 */
public final class AssistantMetrics {

    public static final String MODULE_OPERATION = "app.module.operation";
    public static final String MODULE = "assistant";

    public static final String TAG_MODULE = "module";
    public static final String TAG_OPERATION = "operation";
    public static final String TAG_OUTCOME = "outcome";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    public static final String OPERATION_STREAM = "stream";

    /**
     * The one-shot image extraction, kept apart from {@link #OPERATION_STREAM} because the two have
     * nothing comparable about them: a stream is paced by a reader, an extraction runs flat out.
     * Averaging them together would hide both.
     */
    public static final String OPERATION_VISION = "vision";

    /** Anthropic token usage, split by direction. */
    public static final String TOKENS = "assistant.tokens";
    public static final String TAG_TYPE = "type";
    public static final String TYPE_PROMPT = "prompt";
    public static final String TYPE_COMPLETION = "completion";

    private AssistantMetrics() {
    }
}
