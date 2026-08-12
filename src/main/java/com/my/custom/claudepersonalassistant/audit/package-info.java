/**
 * Observability listeners for the {@link com.my.custom.claudepersonalassistant.chat.ChatCreatedEvent},
 * {@link com.my.custom.claudepersonalassistant.chat.ChatDeletedEvent} and {@link
 * com.my.custom.claudepersonalassistant.assistant.AssistantErrorEvent} events published by
 * {@code chat} and {@code assistant} respectively — the only two modules it may depend on.
 */
@ApplicationModule(allowedDependencies = { "chat", "assistant" })
package com.my.custom.claudepersonalassistant.audit;

import org.springframework.modulith.ApplicationModule;
