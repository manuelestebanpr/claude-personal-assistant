package com.my.custom.claudepersonalassistant.chat.persistence;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findByConversationIdOrderByIdAsc(Long conversationId);

    List<MessageEntity> findByConversationIdOrderByIdDesc(Long conversationId, Limit limit);

    void deleteByConversationId(Long conversationId);
}
