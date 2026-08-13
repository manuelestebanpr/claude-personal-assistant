package com.my.custom.claudepersonalassistant.assistant.logging;

import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * Structured logging of Anthropic content blocks: block-type transitions and per-block chunk
 * counts at INFO, deltas at DEBUG (content at TRACE), final finish reason and token usage at
 * INFO. Log lines carry key-values and are correlated to traces via OTel.
 */
@Component
public class ContentBlockLogger {

    static final String TRANSITION_MESSAGE = "Content block transition";
    static final String DELTA_MESSAGE = "Content block delta";
    static final String DELTA_CONTENT_MESSAGE = "Content block delta content";
    static final String COMPLETED_MESSAGE = "Assistant stream completed";
    static final String FAILED_MESSAGE = "Assistant stream terminated with error";

    static final String KEY_CONVERSATION_ID = "conversationId";
    static final String KEY_BLOCK_TYPE = "blockType";
    static final String KEY_PREVIOUS_BLOCK_TYPE = "previousBlockType";
    static final String KEY_CHUNKS_IN_PREVIOUS_BLOCK = "chunksInPreviousBlock";
    static final String KEY_CHUNK_INDEX = "chunkIndex";
    static final String KEY_CONTENT = "content";
    static final String KEY_FINISH_REASON = "finishReason";
    static final String KEY_PROMPT_TOKENS = "promptTokens";
    static final String KEY_COMPLETION_TOKENS = "completionTokens";
    static final String KEY_TOTAL_CHUNKS = "totalChunks";
    static final String KEY_ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(ContentBlockLogger.class);

    private final MeterRegistry meterRegistry;

    public ContentBlockLogger(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public StreamSession newSession(Long conversationId) {
        return new StreamSession(conversationId);
    }

    /**
     * Per-stream state: tracks the current block type, chunk counts, and the final finish
     * reason / usage. Reactor guarantees serialized signals, so no synchronization is needed.
     */
    public final class StreamSession {

        private final Long conversationId;
        private BlockType currentBlock;
        private long chunksInCurrentBlock;
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

        public void onError(Throwable error) {
            log.atInfo()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_ERROR, error.getClass().getSimpleName())
                    .addKeyValue(KEY_TOTAL_CHUNKS, totalChunks)
                    .log(FAILED_MESSAGE);
        }

        private void logGenerationChunk(AssistantMessage output) {
            BlockType blockType = BlockType.fromMetadata(output.getMetadata());
            if (blockType != currentBlock) {
                logTransition(blockType);
                currentBlock = blockType;
                chunksInCurrentBlock = 0;
            }
            chunksInCurrentBlock++;
            totalChunks++;
            log.atDebug()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_BLOCK_TYPE, blockType)
                    .addKeyValue(KEY_CHUNK_INDEX, totalChunks)
                    .log(DELTA_MESSAGE);
            log.atTrace()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_BLOCK_TYPE, blockType)
                    .addKeyValue(KEY_CONTENT, output.getText())
                    .log(DELTA_CONTENT_MESSAGE);
        }

        private void logTransition(BlockType nextBlock) {
            log.atInfo()
                    .addKeyValue(KEY_CONVERSATION_ID, conversationId)
                    .addKeyValue(KEY_BLOCK_TYPE, nextBlock)
                    .addKeyValue(KEY_PREVIOUS_BLOCK_TYPE, currentBlock)
                    .addKeyValue(KEY_CHUNKS_IN_PREVIOUS_BLOCK, chunksInCurrentBlock)
                    .log(TRANSITION_MESSAGE);
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
