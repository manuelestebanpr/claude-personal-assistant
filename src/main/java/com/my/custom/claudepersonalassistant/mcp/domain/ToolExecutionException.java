package com.my.custom.claudepersonalassistant.mcp.domain;

/**
 * A tool ran and failed. Reported to the caller as a result carrying {@code isError}, never as a
 * JSON-RPC error: the specification reserves protocol errors for malformed requests, and expects
 * execution failures to come back in a shape the model can read and retry from.
 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
