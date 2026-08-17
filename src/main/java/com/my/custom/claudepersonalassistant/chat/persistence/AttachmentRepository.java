package com.my.custom.claudepersonalassistant.chat.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Attachments by message.
 *
 * <p>The {@code findMetadataByMessageIdIn} projection exists so rendering a conversation never loads
 * the image bodies: a chat with twenty photographs would otherwise pull every byte of them out of
 * the database to draw a page that only needs their ids.
 */
public interface AttachmentRepository extends JpaRepository<AttachmentEntity, Long> {

    /**
     * Id, owning message and media type only — no {@code data}.
     *
     * @return rows ordered by id, so the images under a message keep the order they were sent in
     */
    @Query("""
            select new com.my.custom.claudepersonalassistant.chat.persistence.AttachmentMetadata(
                    a.id, a.messageId, a.mediaType)
            from AttachmentEntity a
            where a.messageId in :messageIds
            order by a.id asc
            """)
    List<AttachmentMetadata> findMetadataByMessageIdIn(Collection<Long> messageIds);

    void deleteByMessageIdIn(Collection<Long> messageIds);
}
