package com.my.custom.claudepersonalassistant.mcp.api;

/**
 * Reads an image the caller is holding, with a prompt this module supplies.
 *
 * <p>An <em>inverse</em> port: unlike the rest of this package, the implementation lives outside
 * {@code mcp} and is called inwards. It is the same inversion {@code assistant::api.ToolExecutor}
 * already uses in the other direction, and it exists for the same reason — {@code mcp} declares
 * {@code allowedDependencies = {}}, so a tool that needs to see an image cannot reach either the
 * module that stores images or the module that talks to the model.
 *
 * <p>So {@code mcp} states what it needs and someone above wires it: {@code chat} may depend on
 * both {@code mcp::api} and {@code assistant::api}, which makes it the only place the two halves
 * can meet. No cycle appears, and {@code ModularityTests} proves it.
 *
 * <p>There may be no implementation at all — booting this module alone is a supported thing to do —
 * so callers should hold an {@code ObjectProvider} and fail with a readable message rather than
 * refusing to start.
 */
public interface ImageAnalysis {

    /**
     * @return exactly what the model produced, with any prefill and trailing stop sequence already
     *         removed
     * @throws ImageAnalysisException when the image is unknown or the model call fails
     */
    String analyze(ImageAnalysisRequest request);
}
