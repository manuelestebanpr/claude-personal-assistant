package com.my.custom.claudepersonalassistant.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the chat module.
 *
 * @param contextWindowSize how many trailing messages to replay as model context;
 *                          {@code 0} (the default) replays the full chat history
 * @param defaultTitle      title given to a freshly created chat
 * @param titleMaxLength    maximum length of a title derived from the first user message
 */
@ConfigurationProperties("chat")
public record ChatProperties(
        @DefaultValue("0") int contextWindowSize,
        @DefaultValue("New chat") String defaultTitle,
        @DefaultValue("60") int titleMaxLength) {
}
