package com.my.custom.claudepersonalassistant.assistant.logging;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
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

import com.my.custom.claudepersonalassistant.assistant.config.AssistantObservations;

import static org.assertj.core.api.Assertions.assertThat;

class ContentBlockLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ContentBlockLogger.class);
    private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();
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
        ContentBlockLogger.StreamSession session = newSession();

        session.onChunk(textChunk("Hel"));
        session.onChunk(textChunk("lo"));
        session.onChunk(metadataChunk(BlockType.METADATA_KEY_THINKING, "pondering"));
        session.onChunk(textChunk("!"));
        session.close();

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

    /**
     * The transition line fires at least once on every turn and describes an internal streaming
     * detail, so it must not sit at INFO alongside the turn summary.
     */
    @Test
    void keepsBlockTransitionsBelowTheTurnSummarySLevel() {
        ContentBlockLogger.StreamSession session = newSession();

        session.onChunk(textChunk("Hi"));
        session.onComplete();

        assertThat(eventsWithMessage(ContentBlockLogger.TRANSITION_MESSAGE))
                .singleElement()
                .extracting(ILoggingEvent::getLevel)
                .isEqualTo(Level.DEBUG);
        assertThat(eventsWithMessage(ContentBlockLogger.COMPLETED_MESSAGE))
                .singleElement()
                .extracting(ILoggingEvent::getLevel)
                .isEqualTo(Level.INFO);
    }

    @Test
    void discriminatesSignatureAndRedactedBlocks() {
        ContentBlockLogger.StreamSession session = newSession();

        session.onChunk(metadataChunk(BlockType.METADATA_KEY_THINKING, "pondering"));
        session.onChunk(metadataChunk(BlockType.METADATA_KEY_SIGNATURE, "sig"));
        session.onChunk(metadataChunk(BlockType.METADATA_KEY_REDACTED_DATA, "opaque"));
        session.close();

        assertThat(eventsWithMessage(ContentBlockLogger.TRANSITION_MESSAGE))
                .extracting(event -> keyValue(event, ContentBlockLogger.KEY_BLOCK_TYPE))
                .containsExactly(BlockType.THINKING, BlockType.SIGNATURE, BlockType.REDACTED);
    }

    /**
     * A tool call carries no metadata key at all, so before {@code hasToolCalls()} became the first
     * discriminator it was accounted for as text and vanished from the block sequence.
     */
    @Test
    void discriminatesAToolUseBlockFromTheTextAroundIt() {
        ContentBlockLogger.StreamSession session = newSession();

        session.onChunk(textChunk("Let me look."));
        session.onChunk(toolCallChunk("get_current_hour"));
        session.close();

        assertThat(eventsWithMessage(ContentBlockLogger.TRANSITION_MESSAGE))
                .extracting(event -> keyValue(event, ContentBlockLogger.KEY_BLOCK_TYPE))
                .containsExactly(BlockType.TEXT, BlockType.TOOL_USE);
    }

    @Test
    void opensOneObservationPerBlockCarryingItsTypeIndexAndSize() {
        ContentBlockLogger.StreamSession session = newSession();

        session.onChunk(textChunk("Hel"));
        session.onChunk(textChunk("lo"));
        session.onChunk(toolCallChunk("get_current_hour"));
        session.onComplete();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo(AssistantObservations.CONTENT_BLOCK, 2);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasAnObservation(context -> context
                        .hasNameEqualTo(AssistantObservations.CONTENT_BLOCK)
                        .hasContextualNameEqualTo("block text")
                        .hasLowCardinalityKeyValue(AssistantObservations.KEY_BLOCK_TYPE, "text")
                        .hasLowCardinalityKeyValue(AssistantObservations.KEY_SYSTEM,
                                AssistantObservations.SYSTEM_ANTHROPIC)
                        .hasHighCardinalityKeyValue(AssistantObservations.KEY_BLOCK_INDEX, "0")
                        .hasHighCardinalityKeyValue(AssistantObservations.KEY_BLOCK_CHUNKS, "2")
                        .hasHighCardinalityKeyValue(AssistantObservations.KEY_BLOCK_CHARS, "5"));
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasAnObservation(context -> context
                        .hasNameEqualTo(AssistantObservations.CONTENT_BLOCK)
                        .hasContextualNameEqualTo("block tool_use")
                        .hasLowCardinalityKeyValue(AssistantObservations.KEY_BLOCK_TYPE, "tool_use")
                        .hasHighCardinalityKeyValue(AssistantObservations.KEY_BLOCK_INDEX, "1")
                        .hasHighCardinalityKeyValue(AssistantObservations.KEY_BLOCK_CHUNKS, "1"));
    }

    @Test
    void nestsBlockObservationsUnderTheCallerSObservation() {
        Observation turn = Observation.createNotStarted("chat.turn.stream", observationRegistry).start();
        try (Observation.Scope scope = turn.openScope()) {
            ContentBlockLogger.StreamSession session = newSession();
            session.onChunk(textChunk("Hi"));
            session.onComplete();
        } finally {
            turn.stop();
        }

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AssistantObservations.CONTENT_BLOCK)
                .that()
                .hasParentObservationEqualTo(turn);
    }

    @Test
    void logsCompletionSummaryWithFinishReasonAndUsage() {
        ContentBlockLogger.StreamSession session = newSession();

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
    void logsErrorTerminationWithTheThrowableSoTheStackTraceSurvives() {
        ContentBlockLogger.StreamSession session = newSession();
        IllegalStateException failure = new IllegalStateException("boom");

        session.onChunk(textChunk("Hi"));
        session.onError(failure);

        List<ILoggingEvent> failures = eventsWithMessage(ContentBlockLogger.FAILED_MESSAGE);
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().getLevel()).isEqualTo(Level.DEBUG);
        assertThat(keyValue(failures.getFirst(), ContentBlockLogger.KEY_ERROR))
                .isEqualTo(IllegalStateException.class.getSimpleName());
        assertThat(failures.getFirst().getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    /** A stream that dies mid-block must not leave the block's span open. */
    @Test
    void stopsTheOpenBlockObservationWhenTheStreamFails() {
        ContentBlockLogger.StreamSession session = newSession();

        session.onChunk(textChunk("Hi"));
        session.onError(new IllegalStateException("boom"));
        session.close();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo(AssistantObservations.CONTENT_BLOCK, 1);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AssistantObservations.CONTENT_BLOCK)
                .that()
                .hasError();
    }

    private ContentBlockLogger.StreamSession newSession() {
        return new ContentBlockLogger(new SimpleMeterRegistry(), observationRegistry).newSession(42L);
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

    /**
     * Shaped like the one chunk Anthropic streaming actually produces for a tool call: no metadata,
     * empty content, and the completed calls attached to the final {@code message_delta}.
     */
    private ChatResponse toolCallChunk(String toolName) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", toolName, "{}")))
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
