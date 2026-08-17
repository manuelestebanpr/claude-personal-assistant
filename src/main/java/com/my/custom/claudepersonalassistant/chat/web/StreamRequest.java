package com.my.custom.claudepersonalassistant.chat.web;

import java.util.List;

/**
 * JSON body of the stream endpoint: the new user message, and any images sent with it.
 *
 * <p>Images ride inside the JSON as base64 rather than arriving as a multipart upload. That keeps
 * one endpoint, one content type and one NDJSON response path, and needs no
 * {@code spring.servlet.multipart.*} configuration — none is set anywhere in this application. The
 * cost is the ~33% base64 overhead, which the browser pays down by downscaling before it encodes.
 *
 * @param images each carrying its own media type, because the server never guesses one from bytes
 */
public record StreamRequest(String content, List<InboundImage> images) {

    /**
     * @param dataBase64 standard base64, no data-URI prefix — the browser strips
     *                   {@code data:image/jpeg;base64,} before sending, since the type is already
     *                   its own field
     */
    public record InboundImage(String mediaType, String dataBase64) {
    }
}
