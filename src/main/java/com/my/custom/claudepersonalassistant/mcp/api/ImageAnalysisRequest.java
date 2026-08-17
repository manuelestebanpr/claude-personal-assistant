package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.List;

/**
 * What a tool wants read out of a stored image.
 *
 * <p>The prompting belongs to the <em>tool</em>, not to whoever runs the model: extracting a
 * receipt is this module's problem, and a prompt owned by the other side would drift from the
 * parser that has to read its output.
 *
 * @param imageId       identifies an image the caller stored; this module never sees the bytes
 * @param prefill       put on the wire as a trailing assistant turn for the model to continue.
 *                      Pair one that opens a code fence with a {@code stopSequences} entry that
 *                      closes it and the reply needs no unwrapping
 * @param stopSequences end generation as soon as one appears
 */
public record ImageAnalysisRequest(long imageId, String systemPrompt, String userPrompt, String prefill,
        List<String> stopSequences, int maxTokens) {
}
