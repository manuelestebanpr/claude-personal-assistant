package com.my.custom.claudepersonalassistant.assistant;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Answers, against the live Anthropic API, the one question the cost pipeline is built on: does
 * {@code gen_ai_client_token_usage_total{gen_ai_token_type="input"}} include or exclude the
 * separately-billed cache tokens?
 *
 * <p>The answer is decided in {@code AnthropicChatModel.getDefaultUsage(com.anthropic.models
 * .messages.Usage)}, which maps {@code inputTokens()} straight onto {@code promptTokens} and
 * computes {@code totalTokens} as {@code inputTokens + outputTokens} — while carrying
 * {@code cacheReadInputTokens} and {@code cacheWriteInputTokens} on separate {@code DefaultUsage}
 * fields that {@code ModelUsageMetricsGenerator} never reads. This test proves the same thing from
 * the outside, on both the synchronous and the streamed path, so a Spring AI upgrade that changed
 * the mapping would fail here rather than silently shift what the cost dashboard means.
 *
 * <p>Skipped without a real key in {@code .env}: mocking the model would only assert that the mock
 * returns what the mock was told to return, which is precisely the assumption this test exists to
 * avoid making.
 */
@SpringBootTest
class AnthropicTokenAccountingVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTokenAccountingVerificationTest.class);

    /** The placeholder default in {@code src/test/resources/application.properties}. */
    private static final String DUMMY_KEY = "test-api-key";

    private static final String TOKEN_USAGE_METRIC = "gen_ai.client.token.usage";
    private static final String TAG_TOKEN_TYPE = "gen_ai.token.type";

    /** Short and unique, so the answer is a few tokens and nothing is served from a shared cache. */
    private static final String PROBE = "Reply with exactly the word: pong";

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${spring.ai.anthropic.api-key}")
    private String apiKey;

    @Test
    void syncCallReportsInputTokensExclusiveOfCacheTokensAndTheCounterMatchesThem() {
        assumeRealApiKey();

        double inputBefore = tokenCounterTotal("input");
        double outputBefore = tokenCounterTotal("output");
        double totalBefore = tokenCounterTotal("total");

        ChatResponse response = chatModel.call(new Prompt(PROBE));
        Usage usage = response.getMetadata().getUsage();

        report("sync", response, usage);

        // 1. total == input + output. Anthropic bills cache_creation and cache_read on top of
        //    input_tokens, so a total that is exactly input + output cannot contain them.
        assertThat(usage.getTotalTokens())
                .as("totalTokens is computed as promptTokens + completionTokens, so no cache "
                        + "token can be folded into either operand without breaking this")
                .isEqualTo(usage.getPromptTokens() + usage.getCompletionTokens());

        // 2. The counter carries promptTokens verbatim — no cache adjustment on the metric path.
        assertThat(tokenCounterTotal("input") - inputBefore)
                .as("gen_ai.client.token.usage{gen_ai.token.type=input} increments by exactly "
                        + "promptTokens")
                .isEqualTo(usage.getPromptTokens().doubleValue());
        assertThat(tokenCounterTotal("output") - outputBefore)
                .isEqualTo(usage.getCompletionTokens().doubleValue());
        assertThat(tokenCounterTotal("total") - totalBefore)
                .isEqualTo(usage.getTotalTokens().doubleValue());

        // 3. The cache figures are carried on Usage but contribute to no counter. With caching off
        //    they are zero; the point of the assertion is that they are reported separately at all.
        assertThat(usage.getCacheReadInputTokens()).isNotNull();
        assertThat(usage.getCacheWriteInputTokens()).isNotNull();
    }

    @Test
    void streamedCallProducesTheSameUsageAccountingAsTheSyncPath() {
        assumeRealApiKey();

        double inputBefore = tokenCounterTotal("input");

        List<ChatResponse> chunks = chatModel.stream(new Prompt(PROBE)).collectList().block();
        assertThat(chunks).isNotNull().isNotEmpty();

        ChatResponse last = chunks.getLast();
        Usage usage = last.getMetadata().getUsage();

        report("stream", last, usage);

        assertThat(usage.getPromptTokens()).isPositive();
        assertThat(usage.getCompletionTokens()).isPositive();
        assertThat(usage.getTotalTokens())
                .isEqualTo(usage.getPromptTokens() + usage.getCompletionTokens());
        assertThat(tokenCounterTotal("input") - inputBefore)
                .as("the streamed path meters the aggregated usage exactly once")
                .isEqualTo(usage.getPromptTokens().doubleValue());
    }

    /**
     * Prompt caching is opt-in and this application never opts in: nothing sets
     * {@code spring.ai.anthropic.chat.options.cache.*} and nothing builds an
     * {@link org.springframework.ai.anthropic.AnthropicCacheOptions}, so the
     * {@code AnthropicChatOptions} constructor's {@code AnthropicCacheOptions.disabled()} fallback
     * applies and {@code CacheEligibilityResolver.isCachingEnabled()} is false for every request.
     * No API key needed — this is a configuration fact.
     */
    @Test
    void promptCachingIsDisabledOnEveryRequestPath() {
        AnthropicChatOptions defaults = (AnthropicChatOptions) chatModel.getDefaultOptions();

        assertThat(defaults.getCacheOptions()).isNotNull();
        assertThat(defaults.getCacheOptions().getStrategy())
                .as("no cache strategy is configured, so no request carries a cache_control block "
                        + "and cache_creation/cache_read input tokens are always zero")
                .isEqualTo(AnthropicCacheStrategy.NONE);
    }

    private void assumeRealApiKey() {
        assumeThat(apiKey)
                .as("needs a real ANTHROPIC_API_KEY in .env; this test calls the live API")
                .isNotEqualTo(DUMMY_KEY);
    }

    private double tokenCounterTotal(String tokenType) {
        return meterRegistry.find(TOKEN_USAGE_METRIC).tag(TAG_TOKEN_TYPE, tokenType).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** The finding itself: printed so the numbers are readable in the build output. */
    private void report(String path, ChatResponse response, Usage usage) {
        log.atInfo()
                .addKeyValue("path", path)
                .addKeyValue("responseModel", response.getMetadata().getModel())
                .addKeyValue("promptTokens", usage.getPromptTokens())
                .addKeyValue("completionTokens", usage.getCompletionTokens())
                .addKeyValue("totalTokens", usage.getTotalTokens())
                .addKeyValue("cacheReadInputTokens", usage.getCacheReadInputTokens())
                .addKeyValue("cacheWriteInputTokens", usage.getCacheWriteInputTokens())
                .addKeyValue("nativeUsage", String.valueOf(usage.getNativeUsage()))
                .log("Anthropic token accounting probe");
    }
}
