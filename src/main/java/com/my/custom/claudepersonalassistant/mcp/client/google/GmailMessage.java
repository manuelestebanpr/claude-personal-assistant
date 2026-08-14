package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.util.StringUtils;

/**
 * A Gmail message as the API returns it.
 *
 * <p>Only the fields the tools surface are mapped; the wire payload is far larger. Reading the
 * headers and the body out of the nested MIME tree is the message's own job so both the search and
 * the read tool get the same answer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailMessage(String id, String threadId, String snippet, Payload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(String mimeType, List<Header> headers, Body body, List<Payload> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(String name, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(String data, Integer size) {
    }

    /** Header lookup is case-insensitive: RFC 5322 field names are, and Gmail echoes them as sent. */
    public String header(String name) {
        if (payload == null || payload.headers() == null) {
            return "";
        }
        return payload.headers().stream()
                .filter(header -> name.equalsIgnoreCase(header.name()))
                .map(Header::value)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    /**
     * The plain-text body, or the snippet when the message carries only HTML.
     *
     * <p>Walks the MIME tree rather than reading {@code payload.body} directly: a message with an
     * attachment or an HTML alternative is {@code multipart/*}, and its top-level body is empty.
     */
    public String plainText() {
        return firstTextPart(payload).orElse(snippet == null ? "" : snippet);
    }

    private Optional<String> firstTextPart(Payload part) {
        if (part == null) {
            return Optional.empty();
        }
        if ("text/plain".equalsIgnoreCase(part.mimeType()) && part.body() != null
                && StringUtils.hasText(part.body().data())) {
            return Optional.of(decode(part.body().data()));
        }
        if (part.parts() == null) {
            return Optional.empty();
        }
        return part.parts().stream()
                .map(this::firstTextPart)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Gmail encodes bodies base64url, and pads inconsistently, so decode leniently. */
    private String decode(String data) {
        try {
            return new String(Base64.getUrlDecoder().decode(data.replace("=", "")),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException undecodable) {
            return "";
        }
    }
}
