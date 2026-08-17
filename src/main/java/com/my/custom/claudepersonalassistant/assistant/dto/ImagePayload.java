package com.my.custom.claudepersonalassistant.assistant.dto;

/**
 * One image handed to the model, as bytes plus the media type they are encoded in
 * ({@code image/jpeg}, {@code image/png}, {@code image/gif}, {@code image/webp} — what Anthropic
 * accepts).
 *
 * <p>Bytes rather than a URL or a file handle because the assistant module owns no storage and may
 * not reach the caller's: whoever has the image has to hand it over whole.
 *
 * <p>Being a record over an array, {@code equals} and {@code hashCode} compare the array by
 * identity. Nothing here relies on value equality, and copying a multi-megabyte image on every
 * comparison would be the worse trade.
 */
public record ImagePayload(String mediaType, byte[] data) {
}
