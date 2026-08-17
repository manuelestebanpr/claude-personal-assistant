package com.my.custom.claudepersonalassistant.chat.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.chat.dto.AttachmentDto;

/**
 * Tells the model what an image it is looking at is called.
 *
 * <p>The model can see an attached image, but it has no way to <em>name</em> one — and a tool that
 * reads an image takes an id, not a picture. MCP tool arguments carry no conversation context, so
 * the id has to travel in the only channel the model reads: the text of the turn.
 *
 * <p>The note is added to the copy handed to the model, never to what is stored. Storage keeps what
 * the user actually typed; annotating it would put machine bookkeeping into the transcript and show
 * it back to them on the next page load.
 *
 * <p>It is added to replayed history too, which is what lets a follow-up turn — "the receipt I sent
 * earlier" — still name an image whose bytes are long out of context.
 */
final class AttachmentNotes {

    private AttachmentNotes() {
    }

    static String annotate(String text, List<AttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return text;
        }
        String note = attachments.stream()
                .map(attachment -> "#%d (%s)".formatted(attachment.id(), attachment.mediaType()))
                .collect(Collectors.joining(", ", "[attached images: ", "]"));
        // An image on its own is a complete message, and the turn still needs non-blank text: an
        // empty text block is rejected outright by the provider.
        return StringUtils.hasText(text) ? "%s%n%n%s".formatted(text, note) : note;
    }
}
