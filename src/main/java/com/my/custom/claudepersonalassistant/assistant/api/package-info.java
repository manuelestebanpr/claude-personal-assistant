/**
 * The assistant module's ports: streaming an answer for a conversation turn
 * ({@link com.my.custom.claudepersonalassistant.assistant.api.AssistantClient}), reading an image
 * in one shot ({@link com.my.custom.claudepersonalassistant.assistant.api.VisionClient}), and the
 * inverse port the module calls back out through
 * ({@link com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor}).
 * Spring AI and Anthropic types never appear here.
 *
 * <p>Exposed as a named interface, so other modules may depend on this package by name
 * while everything outside the declared interfaces stays module-internal.
 */
@NamedInterface
package com.my.custom.claudepersonalassistant.assistant.api;

import org.springframework.modulith.NamedInterface;
