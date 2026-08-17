package com.my.custom.claudepersonalassistant.mcp.api;

/**
 * An image could not be read — it is not there, or the model call failed.
 *
 * <p>One type for both because the tool does the same thing with either: turn it into a result the
 * model can read and act on. Distinguishing them would give the caller a choice it has no use for.
 */
public class ImageAnalysisException extends RuntimeException {

    public ImageAnalysisException(String message) {
        super(message);
    }

    public ImageAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
