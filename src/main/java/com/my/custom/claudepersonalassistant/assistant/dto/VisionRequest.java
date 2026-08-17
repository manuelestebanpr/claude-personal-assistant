package com.my.custom.claudepersonalassistant.assistant.dto;

import java.util.List;

/**
 * A single-shot request to read an image: no conversation, no history, no tools.
 *
 * <p>Every field is per-call rather than configured, because the caller is the one that knows what
 * it is extracting. In particular:
 *
 * <ul>
 * <li>{@code systemPrompt} replaces the conversational persona for this call. The personal
 * assistant's prompt tells the model to refuse without a checkable source, which would make it
 * decline to read a photograph.</li>
 * <li>{@code assistantPrefill} is put on the wire as a trailing assistant turn, so the model
 * continues it instead of starting fresh — the cheapest way to force a shape on the output. It is
 * <strong>model-gated</strong>: Anthropic rejects a prefill with a 400 on Opus and Sonnet 4.6 and
 * later, and accepts it on Haiku 4.5. Null or blank sends no prefill at all; an <em>empty</em>
 * assistant turn is itself a 400.</li>
 * <li>{@code stopSequences} ends generation as soon as one appears. Pairing a prefill that opens a
 * fence with a stop sequence that closes it is what makes the reply parseable with no stripping.</li>
 * </ul>
 */
public record VisionRequest(String systemPrompt, String userPrompt, ImagePayload image,
        String assistantPrefill, List<String> stopSequences, Integer maxTokens) {
}
