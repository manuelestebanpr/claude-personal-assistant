package com.my.custom.claudepersonalassistant.chat.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    List<ConversationEntity> findAllByOrderByCreatedAtDescIdDesc();
}
