package com.my.custom.claudepersonalassistant.chat.config;

/**
 * Meter names, observation names and tag keys emitted by the {@code chat} module.
 *
 * <p>A boundary timer is named {@code app.module.operation} and distinguishes its module with the
 * {@code module} tag, so one dashboard panel covers every module that has one. Only {@code
 * assistant} and {@code chat} do: {@code mcp} times {@code mcp.tool} instead and {@code audit}
 * declares no timer at all, so the {@code module} tag currently carries two values, not four. The
 * constants are duplicated per module rather than shared: a module that could import another
 * module's metric names would be depending on it.
 *
 * <p>The observations are a second, separate axis: a timer is not a span, so without them a trace
 * jumps from the servlet span straight into Spring AI's and everything this module does while the
 * answer streams — accumulating deltas, writing NDJSON, persisting the answer, mapping a failure
 * onto an error line — leaves no record. Low-cardinality keys become meter tags, so every
 * observation must set <em>all</em> of its low-cardinality keys on every path; a tag that appears
 * only on failures produces two meters of the same name with different tag sets.
 */
public final class ChatMetrics {

    public static final String MODULE_OPERATION = "app.module.operation";
    public static final String MODULE = "chat";

    public static final String TAG_MODULE = "module";
    public static final String TAG_OPERATION = "operation";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_ERROR_CLASSIFICATION = "error.classification";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    /** Placeholder so {@link #TAG_ERROR_CLASSIFICATION} is present on the successful path too. */
    public static final String CLASSIFICATION_NONE = "none";

    public static final String OBSERVATION_PREPARE_TURN = "chat.turn.prepare";
    public static final String OBSERVATION_PREPARE_TURN_CONTEXTUAL_NAME = "chat turn prepare";
    public static final String OBSERVATION_STREAM_TURN = "chat.turn.stream";
    public static final String OBSERVATION_STREAM_TURN_CONTEXTUAL_NAME = "chat turn stream";

    public static final String KEY_CHAT_ID = "chat.id";
    public static final String KEY_CONTEXT_WINDOW_MESSAGES = "chat.context.window.messages";
    public static final String KEY_TOOLS_OFFERED = "chat.tools.offered";
    /**
     * High-cardinality, like the rest of these: images are what make a turn expensive, and the
     * count is what explains a prompt-token spike on the cost dashboard after the fact.
     */
    public static final String KEY_IMAGES_ATTACHED = "chat.images.attached";
    public static final String KEY_ANSWER_CHARACTERS = "chat.answer.chars";
    public static final String KEY_ANSWER_PERSISTED = "chat.answer.persisted";
    public static final String KEY_STREAM_EVENTS = "chat.stream.events";

    public static final String OPERATION_LIST_CONVERSATIONS = "listConversations";
    public static final String OPERATION_LIST_ASSISTANTS = "listAssistants";
    public static final String OPERATION_CREATE_CONVERSATION = "createConversation";
    public static final String OPERATION_OPEN_CONVERSATION = "openConversation";
    public static final String OPERATION_DELETE_CONVERSATION = "deleteConversation";
    public static final String OPERATION_PREPARE_TURN = "prepareTurn";
    public static final String OPERATION_STREAM_TURN = "streamTurn";
    public static final String OPERATION_LIST_SERVERS = "listServers";
    public static final String OPERATION_LIST_TOOLS = "listTools";
    public static final String OPERATION_EXECUTE_TOOL = "executeTool";

    private ChatMetrics() {
    }
}
