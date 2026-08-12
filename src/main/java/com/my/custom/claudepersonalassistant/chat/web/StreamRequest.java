package com.my.custom.claudepersonalassistant.chat.web;

/**
 * JSON body of the stream endpoint: the new user message.
 */
public record StreamRequest(String content) {
}
