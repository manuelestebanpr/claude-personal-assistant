package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * A conversation as exposed to the web layer and other modules.
 *
 * <p>Creation time is deliberately absent: it orders the list in the repository query
 * ({@code findAllByOrderByCreatedAtDescIdDesc}) and never leaves it. This record reaches the browser
 * only through Thymeleaf, which renders {@code id()} and {@code title()} — unlike
 * {@link ChatMessageDto}, it is never serialised as a REST response body, so an unused component
 * here is dead weight rather than part of a contract.
 *
 * @param assistantId the assistant the conversation was created for; {@code null} on rows that
 *                    predate assistants, which every consumer treats as the default one
 */
public record ConversationDto(Long id, String title, String assistantId) {
}
