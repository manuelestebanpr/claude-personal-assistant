package com.my.custom.claudepersonalassistant.chat;

import java.util.function.Consumer;

/**
 * A chat turn prepared by {@link ChatFacade#prepareTurn}: the chat's existence is already
 * validated and the user message already persisted, so only the (potentially slow) assistant
 * call remains. {@link #stream} runs it, invoking {@code sink} for each event as it is
 * produced, and persists the resulting answer when the turn terminates.
 */
@FunctionalInterface
public interface ChatTurn {

    void stream(Consumer<StreamEvent> sink);
}
