/**
 * Conversation lifecycle, persistence, and the web UI.
 *
 * <p>Depends only on named interfaces of {@code assistant} — Spring AI / Anthropic types never
 * leak past {@link com.my.custom.claudepersonalassistant.assistant.api.AssistantClient}. Naming
 * the interfaces rather than the whole module means {@code ApplicationModules.verify()} rejects a
 * reach into {@code assistant}'s internals, a future dependency on {@code audit}, and any use of
 * an {@code assistant} type that is not deliberately published.
 *
 * <p>Its own published surface is {@code api}, {@code dto} and {@code event}; {@code config},
 * {@code service}, {@code persistence} and {@code web} are internal.
 */
@ApplicationModule(allowedDependencies = {
        "assistant::api",
        "assistant::dto",
        "assistant::exception",
        "mcp::api" })
package com.my.custom.claudepersonalassistant.chat;

import org.springframework.modulith.ApplicationModule;
