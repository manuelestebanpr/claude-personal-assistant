/**
 * Conversation lifecycle, persistence, and the web UI. Depends only on {@code assistant}'s
 * root API — Spring AI / Anthropic types never leak past {@link
 * com.my.custom.claudepersonalassistant.assistant.AssistantClient}. Declared explicitly so
 * {@code ApplicationModules.verify()} rejects a future dependency on {@code audit} as well as
 * any reach into another module's internal packages.
 */
@ApplicationModule(allowedDependencies = "assistant")
package com.my.custom.claudepersonalassistant.chat;

import org.springframework.modulith.ApplicationModule;
