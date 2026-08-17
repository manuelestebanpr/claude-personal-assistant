package com.my.custom.claudepersonalassistant.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the chat module.
 *
 * <p>Every value has a {@link DefaultValue} and none is written into a properties file, which is
 * deliberate: {@code src/test/resources/application.properties} <em>shadows</em> the main one rather
 * than merging with it, so a setting added to only one of them would apply in one run mode and
 * silently not in the others. Defaults declared here apply everywhere, and an override can still be
 * added to both files the day one is actually wanted.
 *
 * @param contextWindowSize    how many trailing messages to replay as model context;
 *                             {@code 0} (the default) replays the full chat history
 * @param defaultTitle         title given to a freshly created chat
 * @param titleMaxLength       maximum length of a title derived from the first user message
 * @param maxImageBytes        largest single decoded image accepted with a message. 5 MB is
 *                             Anthropic's own per-image ceiling, so a larger one could be stored
 *                             and then never sent
 * @param maxImagesPerMessage  how many images one message may carry. The browser downscales before
 *                             upload, so this bounds the request rather than the pixels
 */
@ConfigurationProperties("chat")
public record ChatProperties(
        @DefaultValue("0") int contextWindowSize,
        @DefaultValue("New chat") String defaultTitle,
        @DefaultValue("60") int titleMaxLength,
        @DefaultValue("5242880") int maxImageBytes,
        @DefaultValue("4") int maxImagesPerMessage) {
}
