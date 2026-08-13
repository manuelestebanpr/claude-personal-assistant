/**
 * The assistant module's port: streaming an answer for a conversation turn.
 * Spring AI and Anthropic types never appear here.
 *
 * <p>Exposed as a named interface, so other modules may depend on this package by name
 * while everything outside the declared interfaces stays module-internal.
 */
@NamedInterface
package com.my.custom.claudepersonalassistant.assistant.api;

import org.springframework.modulith.NamedInterface;
