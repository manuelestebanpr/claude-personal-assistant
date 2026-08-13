package com.my.custom.claudepersonalassistant.assistant.config;

/**
 * Meter names and tag keys emitted by the {@code assistant} module.
 *
 * <p>Every module names its boundary timer {@code app.module.operation} and distinguishes itself
 * with the {@code module} tag, so one dashboard panel covers them all. The constants are
 * duplicated per module rather than shared: a module that could import another module's metric
 * names would be depending on it.
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

    /** Anthropic token usage, split by direction. */
    public static final String TOKENS = "assistant.tokens";
    public static final String TAG_TYPE = "type";
    public static final String TYPE_PROMPT = "prompt";
    public static final String TYPE_COMPLETION = "completion";

    private AssistantMetrics() {
    }
}
