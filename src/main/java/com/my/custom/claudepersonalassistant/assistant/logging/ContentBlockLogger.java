package com.my.custom.claudepersonalassistant.assistant.logging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.assistant.config.AssistantMetrics;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantObservations;

/**
 * Structured logging and tracing of Anthropic content blocks: the turn summary (finish reason and
 * token usage) at INFO, everything per-block or per-chunk at DEBUG, raw model output at TRACE.
 *
 * <p>It also opens one {@link AssistantObservations#CONTENT_BLOCK} child observation per block —
 * per <em>block</em>, a handful per turn, never per chunk, of which there are hundreds. This class
 * already owns transition detection, which is exactly the signal a block span needs for its start
 * and end, so putting the span anywhere else would mean detecting transitions twice.
 */
@Component
public class ContentBlockLogger {

    static final String TRANSITION_MESSAGE = "Content block transition";
    static final String DELTA_MESSAGE = "Content block delta";
    static final String DELTA_CONTENT_MESSAGE = "Content block delta content: {}";
    static final String COMPLETED_MESSAGE = "Assistant stream completed";
    static final String FAILED_MESSAGE = "Assistant stream terminated with error";

    static final String KEY_CONVERSATION_ID = "conversationId";
    static final String KEY_BLOCK_TYPE = "blockType";
    static final String KEY_PREVIOUS_BLOCK_TYPE = "previousBlockType";
    static final String KEY_CHUNKS_IN_PREVIOUS_BLOCK = "chunksInPreviousBlock";
    static final String KEY_CHUNK_INDEX = "chunkIndex";
    static final String KEY_FINISH_REASON = "finishReason";
    static final String KEY_PROMPT_TOKENS = "promptTokens";
    static final String KEY_COMPLETION_TOKENS = "completionTokens";
    static final String KEY_TOTAL_CHUNKS = "totalChunks";
    static final String KEY_ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(ContentBlockLogger.class);

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public ContentBlockLogger(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    public StreamSession newSession(Long conversationId) {
        return new StreamSession(conversationId);
    }

    /**
     * Per-stream state: tracks the current block type, chunk counts, the open block observation,
     * and the final finish reason / usage. Reactor guarantees serialized signals, so no
     * synchronization is needed.
     *
     * <p>{@link AutoCloseable} because an unstopped observation is a leaked span: whatever ends the
     * stream — completion, a classified failure, a client disconnect, or an {@code Error} that no
     * catch block claims — {@link #close()} still stops the block that was open, and is idempotent
     * so the normal paths can stop it earlier with the right outcome.
     */
    public final class StreamSession implements AutoCloseable {

        private final Long conversationId;
        private BlockType currentBlock;
        private long chunksInCurrentBlock;
        private long charactersInCurrentBlock;
        private long blocksOpened;
        private Observation blockObservation;
        private long totalChunks;
        private String finishReason;
        private Usage usage;

        private StreamSession(Long conversationId) {
            this.conversationId = conversationId;
        }

        public void onChunk(ChatResponse response) {
            if (response == null) {
                return;
            }
            for (Generation generation : response.getResults()) {
                AssistantMessage output = generation.getOutput();
                logGenerationChunk(output);
                rememberFinishReason(generation.getMetadata());
            }
            rememberUsage(response);
        }

        public void onComplete() {
            stopBlockObservation();
            log.atInfo()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_FINISH_REASON, finishReason)
                    .addKeyValue(KEY_PROMPT_TOKENS, usage != null ? usage.getPromptTokens() : null)
                    .addKeyValue(KEY_COMPLETION_TOKENS, usage != null ? usage.getCompletionTokens() : null)
                    .addKeyValue(KEY_TOTAL_CHUNKS, totalChunks)
                    .log(COMPLETED_MESSAGE);
            recordTokenUsage();
        }

        /**
         * Token usage is only known once the stream finishes, and this is the one place that
         * already holds it — counting it here avoids threading the figure back out through the
         * client just to meter it.
         */
        private void recordTokenUsage() {
            if (usage == null) {
                return;
            }
            countTokens(AssistantMetrics.TYPE_PROMPT, usage.getPromptTokens());
            countTokens(AssistantMetrics.TYPE_COMPLETION, usage.getCompletionTokens());
        }

        private void countTokens(String type, Integer tokens) {
            if (tokens == null || tokens <= 0) {
                return;
            }
            meterRegistry.counter(AssistantMetrics.TOKENS, AssistantMetrics.TAG_TYPE, type)
                    .increment(tokens);
        }

        /**
         * DEBUG, not INFO: the failure itself is already reported once, and better — {@code
         * AssistantErrorAuditor} counts it on {@code assistant.stream.errors} with the
         * classification and error type, and the block observation stopped just above carries the
         * throwable onto the span. This line only adds where in the block sequence the stream died,
         * which is a detail for someone debugging streaming, not a turn-level fact.
         */
        public void onError(Throwable error) {
            stopBlockObservation(error);
            log.atDebug()
                    .setCause(error)
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_ERROR, error.getClass().getSimpleName())
                    .addKeyValue(KEY_TOTAL_CHUNKS, totalChunks)
                    .log(FAILED_MESSAGE);
        }

        @Override
        public void close() {
            stopBlockObservation();
        }

        private void logGenerationChunk(AssistantMessage output) {
            BlockType blockType = BlockType.of(output);
            if (blockType != currentBlock) {
                logTransition(blockType);
                stopBlockObservation();
                currentBlock = blockType;
                chunksInCurrentBlock = 0;
                charactersInCurrentBlock = 0;
                startBlockObservation(blockType);
            }
            chunksInCurrentBlock++;
            totalChunks++;
            String text = output.getText();
            charactersInCurrentBlock += text != null ? text.length() : 0;
            log.atDebug()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_BLOCK_TYPE, blockType)
                    .addKeyValue(KEY_CHUNK_INDEX, totalChunks)
                    .log(DELTA_MESSAGE);
            // The raw text goes in the message body, not in an addKeyValue attribute. Key-values
            // are exported as OTLP structured metadata, and Loki rejects the *whole* entry with a
            // 400 once an entry's structured metadata passes max_structured_metadata_size (~64 KB)
            // rather than trimming the offending field — so one long delta would cost the line
            // outright. In the body the governing limit is max_line_size instead.
            log.atTrace()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_BLOCK_TYPE, blockType)
                    .log(DELTA_CONTENT_MESSAGE, text);
        }

        /**
         * DEBUG, not INFO: this fires at least once per turn (the first chunk always transitions
         * away from a null block) and is an internal detail of the stream — no dashboard panel and
         * no human reads it. The turn summary in {@link #onComplete()} is the INFO-level line.
         */
        private void logTransition(BlockType nextBlock) {
            log.atDebug()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_BLOCK_TYPE, nextBlock)
                    .addKeyValue(KEY_PREVIOUS_BLOCK_TYPE, currentBlock)
                    .addKeyValue(KEY_CHUNKS_IN_PREVIOUS_BLOCK, chunksInCurrentBlock)
                    .log(TRANSITION_MESSAGE);
        }

        private void startBlockObservation(BlockType blockType) {
            blockObservation = Observation.createNotStarted(AssistantObservations.CONTENT_BLOCK, observationRegistry)
                    .contextualName(blockType.contextualName())
                    .lowCardinalityKeyValue(AssistantObservations.KEY_BLOCK_TYPE, blockType.tag())
                    .lowCardinalityKeyValue(AssistantObservations.KEY_SYSTEM, AssistantObservations.SYSTEM_ANTHROPIC)
                    .highCardinalityKeyValue(AssistantObservations.KEY_BLOCK_INDEX, String.valueOf(blocksOpened))
                    .start();
            blocksOpened++;
        }

        private void stopBlockObservation() {
            stopBlockObservation(null);
        }

        /** Chunk and character counts are only final at this point, so they are added on the way out. */
        private void stopBlockObservation(Throwable error) {
            if (blockObservation == null) {
                return;
            }
            blockObservation
                    .highCardinalityKeyValue(AssistantObservations.KEY_BLOCK_CHUNKS,
                            String.valueOf(chunksInCurrentBlock))
                    .highCardinalityKeyValue(AssistantObservations.KEY_BLOCK_CHARS,
                            String.valueOf(charactersInCurrentBlock));
            if (error != null) {
                blockObservation.error(error);
            }
            blockObservation.stop();
            blockObservation = null;
        }

        private void rememberFinishReason(ChatGenerationMetadata metadata) {
            if (metadata != null && StringUtils.hasText(metadata.getFinishReason())) {
                finishReason = metadata.getFinishReason();
            }
        }

        private void rememberUsage(ChatResponse response) {
            if (response.getMetadata() == null) {
                return;
            }
            Usage candidate = response.getMetadata().getUsage();
            if (candidate != null && candidate.getTotalTokens() != null && candidate.getTotalTokens() > 0) {
                usage = candidate;
            }
        }
    }
}
