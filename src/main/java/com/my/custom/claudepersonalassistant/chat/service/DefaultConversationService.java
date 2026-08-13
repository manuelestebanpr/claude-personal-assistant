package com.my.custom.claudepersonalassistant.chat.service;

import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.custom.claudepersonalassistant.chat.config.ChatProperties;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.event.ChatCreatedEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatDeletedEvent;
import com.my.custom.claudepersonalassistant.chat.persistence.ConversationEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.ConversationRepository;

@Service
@Transactional
@RequiredArgsConstructor
class DefaultConversationService implements ConversationService {

    private final ConversationRepository conversations;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatProperties properties;

    @Override
    @Transactional(readOnly = true)
    public List<ConversationDto> list() {
        return conversations.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public ConversationDto create() {
        ConversationEntity entity = new ConversationEntity();
        entity.setTitle(properties.defaultTitle());
        entity.setCreatedAt(Instant.now());
        ConversationEntity saved = conversations.save(entity);
        eventPublisher.publishEvent(new ChatCreatedEvent(saved.getId(), saved.getTitle()));
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationDto get(Long chatId) {
        return toDto(require(chatId));
    }

    @Override
    public void applyDerivedTitle(Long chatId, String firstUserText) {
        ConversationEntity entity = require(chatId);
        if (!properties.defaultTitle().equals(entity.getTitle())) {
            return;
        }
        entity.setTitle(deriveTitle(firstUserText));
        conversations.save(entity);
    }

    @Override
    public void delete(Long chatId) {
        ConversationEntity entity = require(chatId);
        messageService.deleteAll(chatId);
        conversations.delete(entity);
        eventPublisher.publishEvent(new ChatDeletedEvent(chatId, entity.getTitle()));
    }

    private ConversationEntity require(Long chatId) {
        return conversations.findById(chatId).orElseThrow(() -> new ChatNotFoundException(chatId));
    }

    private String deriveTitle(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return properties.defaultTitle();
        }
        int maxLength = properties.titleMaxLength();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private ConversationDto toDto(ConversationEntity entity) {
        return new ConversationDto(entity.getId(), entity.getTitle(), entity.getCreatedAt());
    }
}
