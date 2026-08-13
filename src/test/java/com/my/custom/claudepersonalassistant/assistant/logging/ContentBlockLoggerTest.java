package com.my.custom.claudepersonalassistant.assistant.logging;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;

class ContentBlockLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ContentBlockLogger.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void logsBlockTransitionsWithPerBlockChunkCounts() {
        ContentBlockLogger.StreamSession session = new ContentBlockLogger(new SimpleMeterRegistry()).newSession(42L);

        session.onChunk(textChunk("Hel"));
        session.onChunk(textChunk("lo"));
        session.onChunk(metadataChunk(BlockType.METADATA_KEY_THINKING, "pondering"));
        session.onChunk(textChunk("!"));

        List<ILoggingEvent> transitions = eventsWithMessage(ContentBlockLogger.TRANSITION_MESSAGE);
        assertThat(transitions).hasSize(3);
        assertThat(transitions)
                .extracting(event -> keyValue(event, ContentBlockLogger.KEY_BLOCK_TYPE))
                .containsExactly(BlockType.TEXT, BlockType.THINKING, BlockType.TEXT);
        assertThat(transitions)
                .extracting(event -> keyValue(event, ContentBlockLogger.KEY_PREVIOUS_BLOCK_TYPE))
                .containsExactly(null, BlockType.TEXT, BlockType.THINKING);
        assertThat(keyValue(transitions.get(1), ContentBlockLogger.KEY_CHUNKS_IN_PREVIOUS_BLOCK)).isEqualTo(2L);
    }

    @Test
    void discriminatesSignatureAndRedactedBlocks() {
        ContentBlockLogger.StreamSession session = new ContentBlockLogger(new SimpleMeterRegistry()).newSession(42L);

        session.onChunk(metadataChunk(BlockType.METADATA_KEY_THINKING, "pondering"));
        session.onChunk(metadataChunk(BlockType.METADATA_KEY_SIGNATURE, "sig"));
        session.onChunk(metadataChunk(BlockType.METADATA_KEY_REDACTED_DATA, "opaque"));

        assertThat(eventsWithMessage(ContentBlockLogger.TRANSITION_MESSAGE))
                .extracting(event -> keyValue(event, ContentBlockLogger.KEY_BLOCK_TYPE))
                .containsExactly(BlockType.THINKING, BlockType.SIGNATURE, BlockType.REDACTED);
    }

    @Test
    void logsCompletionSummaryWithFinishReasonAndUsage() {
        ContentBlockLogger.StreamSession session = new ContentBlockLogger(new SimpleMeterRegistry()).newSession(42L);

        session.onChunk(textChunk("Hi"));
        session.onChunk(finalChunk("end_turn", new DefaultUsage(10, 20)));
        session.onComplete();

        List<ILoggingEvent> summaries = eventsWithMessage(ContentBlockLogger.COMPLETED_MESSAGE);
        assertThat(summaries).hasSize(1);
        ILoggingEvent summary = summaries.getFirst();
        assertThat(keyValue(summary, ContentBlockLogger.KEY_FINISH_REASON)).isEqualTo("end_turn");
        assertThat(keyValue(summary, ContentBlockLogger.KEY_PROMPT_TOKENS)).isEqualTo(10);
        assertThat(keyValue(summary, ContentBlockLogger.KEY_COMPLETION_TOKENS)).isEqualTo(20);
        assertThat(keyValue(summary, ContentBlockLogger.KEY_TOTAL_CHUNKS)).isEqualTo(2L);
    }

    @Test
    void logsErrorTermination() {
        ContentBlockLogger.StreamSession session = new ContentBlockLogger(new SimpleMeterRegistry()).newSession(42L);

        session.onChunk(textChunk("Hi"));
        session.onError(new IllegalStateException("boom"));

        List<ILoggingEvent> failures = eventsWithMessage(ContentBlockLogger.FAILED_MESSAGE);
        assertThat(failures).hasSize(1);
        assertThat(keyValue(failures.getFirst(), ContentBlockLogger.KEY_ERROR))
                .isEqualTo(IllegalStateException.class.getSimpleName());
    }

    private List<ILoggingEvent> eventsWithMessage(String message) {
        return appender.list.stream().filter(event -> message.equals(event.getMessage())).toList();
    }

    private Object keyValue(ILoggingEvent event, String key) {
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            if (key.equals(pair.key)) {
                return pair.value;
            }
        }
        return null;
    }

    private ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatResponse metadataChunk(String metadataKey, String value) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of(metadataKey, value))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse finalChunk(String finishReason, DefaultUsage usage) {
        Generation generation = new Generation(new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(ChatResponseMetadata.builder().usage(usage).build())
                .build();
    }
}
