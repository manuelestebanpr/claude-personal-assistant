package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * An image arriving with a new message, already decoded from whatever the transport wrapped it in.
 *
 * <p>Deliberately not Spring AI's {@code Media}: {@code chat} may depend on {@code assistant::dto}
 * but never on Spring AI itself, and {@code ModularityTests} is what enforces that. The conversion
 * happens once, at the boundary, in {@code DefaultChatFacade}.
 *
 * <p>Like every record over an array, equality is by array identity. Nothing compares these.
 */
public record ImageUpload(String mediaType, byte[] data) {
}
