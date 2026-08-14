package com.my.custom.claudepersonalassistant.assistant.dto;

/**
 * The outcome of a tool the model called mid-turn.
 *
 * @param text  the tool's textual output, or the failure description when {@code error} is set
 * @param error whether the tool ran and failed — a business failure the model can read and act
 *              on, as opposed to a port/protocol failure, which propagates as an exception
 */
public record ToolExecutionResult(String text, boolean error) {

    public static ToolExecutionResult ok(String text) {
        return new ToolExecutionResult(text, false);
    }

    public static ToolExecutionResult failed(String text) {
        return new ToolExecutionResult(text, true);
    }
}
