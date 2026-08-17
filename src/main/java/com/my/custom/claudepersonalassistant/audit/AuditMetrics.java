package com.my.custom.claudepersonalassistant.audit;

/**
 * Metric names and tag keys emitted by the audit module.
 *
 * <p><strong>{@link #TOOLS_INVOKED} is not a duplicate of {@code mcp.tool.invocations}.</strong>
 * They count the same events today and will not always: this one is event-driven and answers "what
 * did the assistant do", incremented by a listener that only runs once the publishing transaction
 * committed; the {@code mcp} one is synchronous and answers "what did the tool server execute",
 * incremented inside the call itself alongside the {@code mcp.tool} timer. The {@code mcp} module is
 * built to be liftable into its own service — {@code allowedDependencies = {}}, its own HTTP
 * endpoint, a client that already speaks to several servers — and on the day that happens the two
 * measure different processes: a tool this application never asked for still moves the {@code mcp}
 * counter, and a call lost between the two moves one and not the other. That gap is the signal.
 * Deleting either one because they agree removes the only way to notice when they stop agreeing.
 */
public final class AuditMetrics {

    public static final String CHATS_CREATED = "assistant.chats.created";
    public static final String CHATS_DELETED = "assistant.chats.deleted";
    public static final String STREAM_ERRORS = "assistant.stream.errors";
    public static final String TOOLS_INVOKED = "assistant.tools.invoked";
    public static final String TOOLS_REJECTED = "assistant.tools.rejected";

    public static final String TAG_CLASSIFICATION = "classification";
    public static final String TAG_ERROR_TYPE = "error.type";
    public static final String TAG_TOOL = "tool";
    public static final String TAG_OUTCOME = "outcome";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    /** Tag value used when the upstream error carried no Anthropic error type. */
    public static final String TAG_VALUE_NONE = "none";

    private AuditMetrics() {
    }
}
