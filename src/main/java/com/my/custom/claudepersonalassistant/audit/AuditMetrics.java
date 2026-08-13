package com.my.custom.claudepersonalassistant.audit;

/**
 * Metric names and tag keys emitted by the audit module.
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
