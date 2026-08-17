package com.my.custom.claudepersonalassistant.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;

/**
 * One persisted message. The storage side of the port whose other side is
 * {@code assistant.dto.HistoryMessage} — and the two are deliberately not one type.
 *
 * <p>{@code HistoryMessage} is the model contract: a role and its text, nothing the model has no
 * use for. This entity is the record: identity, the owning conversation, a timestamp and a
 * {@link Lob} body. {@code DefaultChatFacade.toHistory()} maps between them, and that mapping is
 * what keeps JPA out of the {@code assistant} module and Spring AI out of {@code chat}. Sharing one
 * type across the boundary would look like a simplification and would couple the two modules'
 * persistence and model concerns permanently; the duplication is the point.
 */
@Entity
@Table(name = "chat_message",
        indexes = @Index(name = "idx_chat_message_conversation_id", columnList = "conversation_id"))
@Getter
@Setter
@NoArgsConstructor
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageRole role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
