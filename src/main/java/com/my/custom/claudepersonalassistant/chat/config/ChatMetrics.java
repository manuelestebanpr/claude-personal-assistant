package com.my.custom.claudepersonalassistant.chat.config;

/**
 * Meter names and tag keys emitted by the {@code chat} module.
 *
 * <p>Every module names its boundary timer {@code app.module.operation} and distinguishes itself
 * with the {@code module} tag, so one dashboard panel covers them all. The constants are
 * duplicated per module rather than shared: a module that could import another module's metric
 * names would be depending on it.
 */
public final class ChatMetrics {

    public static final String MODULE_OPERATION = "app.module.operation";
    public static final String MODULE = "chat";

    public static final String TAG_MODULE = "module";
    public static final String TAG_OPERATION = "operation";
    public static final String TAG_OUTCOME = "outcome";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    public static final String OPERATION_LIST_CONVERSATIONS = "listConversations";
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
