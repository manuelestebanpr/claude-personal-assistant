package com.my.custom.claudepersonalassistant.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An image sent with a message, stored whole.
 *
 * <p>In the database rather than on disk because the database is already the thing that survives a
 * restart, gets backed up, and is deleted with its conversation. A filesystem path would be a
 * second store to keep consistent with the first, and orphaned files are the usual result.
 *
 * <p>Its own table rather than a column on {@code MessageEntity}: one message may carry several
 * images, and a {@code @Lob} that is usually absent would be loaded on every history read for
 * nothing. Same plain-FK style as {@code MessageEntity} — no {@code @ManyToOne}, so a history read
 * never drags a graph behind it.
 */
@Entity
@Table(name = "chat_attachment",
        indexes = @Index(name = "idx_chat_attachment_message_id", columnList = "message_id"))
@Getter
@Setter
@NoArgsConstructor
public class AttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "media_type", nullable = false, length = 64)
    private String mediaType;

    @Lob
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
