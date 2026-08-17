package com.my.custom.claudepersonalassistant.chat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.custom.claudepersonalassistant.chat.config.ChatProperties;
import com.my.custom.claudepersonalassistant.chat.dto.AttachmentDto;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ImageUpload;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentMetadata;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentRepository;
import com.my.custom.claudepersonalassistant.chat.persistence.MessageEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.MessageRepository;

@Service
@RequiredArgsConstructor
class DefaultMessageService implements MessageService {

    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final ChatProperties properties;

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageDto> history(Long chatId) {
        return withAttachments(messages.findByConversationIdOrderByIdAsc(chatId));
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
        return withAttachments(latestFirst);
    }

    @Override
    @Transactional
    public ChatMessageDto append(Long chatId, MessageRole role, String content) {
        return append(chatId, role, content, List.of());
    }

    @Override
    @Transactional
    public ChatMessageDto append(Long chatId, MessageRole role, String content, List<ImageUpload> images) {
        Instant now = Instant.now();
        MessageEntity entity = new MessageEntity();
        entity.setConversationId(chatId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(now);
        MessageEntity saved = messages.save(entity);
        return new ChatMessageDto(saved.getId(), saved.getRole(), saved.getContent(), saved.getCreatedAt(),
                store(saved.getId(), images, now));
    }

    @Override
    @Transactional
    public void deleteAll(Long chatId) {
        List<Long> messageIds = messages.findByConversationIdOrderByIdAsc(chatId).stream()
                .map(MessageEntity::getId)
                .toList();
        if (!messageIds.isEmpty()) {
            // Before the messages, so a failure here cannot leave rows pointing at a message that is
            // already gone. Nothing cascades: the FK is a plain column, by the same choice
            // MessageEntity made.
            attachments.deleteByMessageIdIn(messageIds);
        }
        messages.deleteByConversationId(chatId);
    }

    private List<AttachmentDto> store(Long messageId, List<ImageUpload> images, Instant now) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<AttachmentEntity> entities = images.stream().map(image -> {
            AttachmentEntity entity = new AttachmentEntity();
            entity.setMessageId(messageId);
            entity.setMediaType(image.mediaType());
            entity.setData(image.data());
            entity.setCreatedAt(now);
            return entity;
        }).toList();
        return attachments.saveAll(entities).stream()
                .map(entity -> new AttachmentDto(entity.getId(), entity.getMediaType()))
                .toList();
    }

    /**
     * One extra query for the whole page rather than one per message — a conversation with thirty
     * messages should not cost thirty round trips to discover that twenty-nine of them have nothing
     * attached.
     */
    private List<ChatMessageDto> withAttachments(List<MessageEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<Long> ids = entities.stream().map(MessageEntity::getId).toList();
        Map<Long, List<AttachmentDto>> byMessage = attachments.findMetadataByMessageIdIn(ids).stream()
                .collect(Collectors.groupingBy(AttachmentMetadata::messageId, Collectors.mapping(
                        metadata -> new AttachmentDto(metadata.id(), metadata.mediaType()),
                        Collectors.toList())));
        return entities.stream()
                .map(entity -> new ChatMessageDto(entity.getId(), entity.getRole(), entity.getContent(),
                        entity.getCreatedAt(), byMessage.getOrDefault(entity.getId(), List.of())))
                .toList();
    }
}
