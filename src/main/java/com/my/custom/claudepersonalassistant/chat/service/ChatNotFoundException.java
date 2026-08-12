package com.my.custom.claudepersonalassistant.chat.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a conversation id does not exist; resolved to a 404 before any stream starts.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ChatNotFoundException extends RuntimeException {

    public ChatNotFoundException(Long chatId) {
        super("Chat not found: " + chatId);
    }
}
