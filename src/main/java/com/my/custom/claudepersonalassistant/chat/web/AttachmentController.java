package com.my.custom.claudepersonalassistant.chat.web;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentRepository;

import java.time.Duration;

/**
 * Serves a stored image back to the browser.
 *
 * <p>The page emits one {@code <img src="/attachments/{id}">} per attachment rather than inlining
 * the bytes as data URIs: a conversation with a dozen photographs would otherwise be a several-
 * megabyte HTML document that has to be rebuilt and re-sent on every reload, with nothing
 * cacheable in it.
 *
 * <p>Which is why the cache header matters — an attachment is immutable once written, so the
 * browser should never ask twice. A year is the conventional ceiling for {@code immutable}.
 *
 * <p>Like every other endpoint here, this is unauthenticated by design: anyone who can reach the
 * port can read any attachment by guessing an id. That is the same premise the chat, the tools and
 * the H2 console already run on, and access scoping belongs in the host firewall, not here.
 */
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    public static final String ATTACHMENT_PATH = "/attachments/{attachmentId}";

    private static final Duration IMMUTABLE_FOR = Duration.ofDays(365);

    private final AttachmentRepository attachments;

    @GetMapping(ATTACHMENT_PATH)
    public ResponseEntity<byte[]> attachment(@PathVariable Long attachmentId) {
        return attachments.findById(attachmentId)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> toResponse(AttachmentEntity attachment) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getMediaType()))
                .cacheControl(CacheControl.maxAge(IMMUTABLE_FOR).cachePublic().immutable())
                .body(attachment.getData());
    }
}
