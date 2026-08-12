package com.my.custom.claudepersonalassistant.audit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.AssistantErrorEvent;

/**
 * Audits classified assistant stream failures with an error log and a tagged counter.
 */
@Component
@RequiredArgsConstructor
class AssistantErrorAuditor {

    private static final Logger log = LoggerFactory.getLogger(AssistantErrorAuditor.class);

    private final MeterRegistry meterRegistry;

    @ApplicationModuleListener
    void onAssistantError(AssistantErrorEvent event) {
        log.atError()
                .addKeyValue("conversationId", event.conversationId())
                .addKeyValue("classification", event.classification())
                .addKeyValue("statusCode", event.statusCode())
                .addKeyValue("errorType", event.errorType())
                .log("Assistant stream error: {}", event.message());
        meterRegistry.counter(AuditMetrics.STREAM_ERRORS,
                        AuditMetrics.TAG_CLASSIFICATION, event.classification().name(),
                        AuditMetrics.TAG_ERROR_TYPE,
                        event.errorType() != null ? event.errorType() : AuditMetrics.TAG_VALUE_NONE)
                .increment();
    }
}
