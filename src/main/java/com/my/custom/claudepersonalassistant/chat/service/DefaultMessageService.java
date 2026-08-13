package com.my.custom.claudepersonalassistant.chat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.custom.claudepersonalassistant.chat.config.ChatProperties;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.persistence.MessageEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.MessageRepository;

@Service
@RequiredArgsConstructor
class DefaultMessageService implements MessageService {

    private final MessageRepository messages;
    private final ChatProperties properties;

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageDto> history(Long chatId) {
        return messages.findByConversationIdOrderByIdAsc(chatId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageDto> contextWindow(Long chatId) {
        int windowSize = properties.contextWindowSize();
        if (windowSize <= 0) {
            return history(chatId);
        }
        List<MessageEntity> latestFirst =
                new ArrayList<>(messages.findByConversationIdOrderByIdDesc(chatId, Limit.of(windowSize)));
        Collections.reverse(latestFirst);
        return latestFirst.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public ChatMessageDto append(Long chatId, MessageRole role, String content) {
        MessageEntity entity = new MessageEntity();
        entity.setConversationId(chatId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(Instant.now());
        return toDto(messages.save(entity));
    }

    @Override
    @Transactional
    public void deleteAll(Long chatId) {
        messages.deleteByConversationId(chatId);
    }

    private ChatMessageDto toDto(MessageEntity entity) {
        return new ChatMessageDto(entity.getId(), entity.getRole(), entity.getContent(), entity.getCreatedAt());
    }
}
