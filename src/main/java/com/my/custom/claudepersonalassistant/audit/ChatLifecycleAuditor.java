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
 *
 * <p><strong>No user content reaches the log backend from here.</strong> The events carry a title,
 * but a title is derived from the user's first message — logging it shipped the opening 60
 * characters of every conversation to Loki as structured metadata, kept for 31 days. With Spring
 * AI's prompt and completion logging now off (see {@code observability.properties}) this was the
 * last remaining path by which message content reached the backend, so it is closed here rather
 * than left as a second, separate decision. {@code chatId} is the join key; the title is one query
 * away in the database, where it already lives and where deleting a chat actually removes it.
 */
@Component
@RequiredArgsConstructor
class ChatLifecycleAuditor {

    private static final Logger log = LoggerFactory.getLogger(ChatLifecycleAuditor.class);

    private static final String KEY_CHAT_ID = "chatId";

    private final MeterRegistry meterRegistry;

    @ApplicationModuleListener
    void onChatCreated(ChatCreatedEvent event) {
        log.atInfo()
                .addKeyValue(KEY_CHAT_ID, event.chatId())
                .log("Chat created");
        meterRegistry.counter(AuditMetrics.CHATS_CREATED).increment();
    }

    @ApplicationModuleListener
    void onChatDeleted(ChatDeletedEvent event) {
        log.atInfo()
                .addKeyValue(KEY_CHAT_ID, event.chatId())
                .log("Chat deleted");
        meterRegistry.counter(AuditMetrics.CHATS_DELETED).increment();
    }
}
