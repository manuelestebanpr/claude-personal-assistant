package com.my.custom.claudepersonalassistant.audit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.chat.event.ChatCreatedEvent;
import com.my.custom.claudepersonalassistant.chat.event.ChatDeletedEvent;

/**
 * Audits chat lifecycle events with structured logs and counters.
 */
@Component
@RequiredArgsConstructor
class ChatLifecycleAuditor {

    private static final Logger log = LoggerFactory.getLogger(ChatLifecycleAuditor.class);

    private final MeterRegistry meterRegistry;

    @ApplicationModuleListener
    void onChatCreated(ChatCreatedEvent event) {
        log.atInfo()
                .addKeyValue("chatId", event.chatId())
                .addKeyValue("title", event.title())
                .log("Chat created");
        meterRegistry.counter(AuditMetrics.CHATS_CREATED).increment();
    }

    @ApplicationModuleListener
    void onChatDeleted(ChatDeletedEvent event) {
        log.atInfo()
                .addKeyValue("chatId", event.chatId())
                .addKeyValue("title", event.title())
                .log("Chat deleted");
        meterRegistry.counter(AuditMetrics.CHATS_DELETED).increment();
    }
}
