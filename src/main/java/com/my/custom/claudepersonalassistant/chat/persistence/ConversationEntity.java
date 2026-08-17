package com.my.custom.claudepersonalassistant.chat.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "conversation")
@Getter
@Setter
@NoArgsConstructor
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** Nullable on purpose: rows created before assistants existed mean "the default assistant". */
    @Column(name = "assistant_id", updatable = false)
    private String assistantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
