package com.my.custom.claudepersonalassistant.assistant;

import java.util.function.Consumer;

/**
 * Module API of the assistant module: streams answer text deltas for a conversation turn.
 * Spring AI / Anthropic types never leak through this interface; failures surface as
 * {@link AssistantException}.
 */
public interface AssistantClient {

    /**
     * Blocks the calling (virtual) thread, invoking {@code onDelta} for each answer chunk as
     * it arrives. Throws {@link AssistantException} if the stream fails.
     */
    void stream(AssistantRequest request, Consumer<String> onDelta);
}
