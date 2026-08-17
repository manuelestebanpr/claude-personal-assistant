package com.my.custom.claudepersonalassistant.assistant.api;

import com.my.custom.claudepersonalassistant.assistant.dto.VisionRequest;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;

/**
 * Reads an image with the model and returns what it said, in one blocking call.
 *
 * <p>Deliberately not a mode of {@link AssistantClient}: that one is a conversation — it replays
 * history, offers tools, and streams token by token so a person can watch it arrive. This is an
 * extraction: one image, one prompt, one answer that nobody reads until it is complete. Sharing a
 * method would mean every caller carrying parameters the other never uses.
 *
 * <p>Blocking is the point rather than a limitation. The caller is a tool invocation that cannot
 * return until it has an answer, and blocking on a virtual thread costs a parked continuation.
 */
public interface VisionClient {

    /**
     * @return the model's reply, with any prefill and trailing stop sequence already removed by the
     *         provider — what comes back is only what the model generated
     * @throws AssistantException classified {@code RETRYABLE} or {@code TERMINAL}, so the caller can
     *                            tell "try again" from "this will never work"
     */
    String extract(VisionRequest request);
}
