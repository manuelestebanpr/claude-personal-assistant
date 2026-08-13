package com.my.custom.claudepersonalassistant.mcp.domain;

/**
 * No tool is registered under the requested name.
 */
public class UnknownToolException extends RuntimeException {

    private final String toolName;

    public UnknownToolException(String toolName) {
        super("Unknown tool: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
