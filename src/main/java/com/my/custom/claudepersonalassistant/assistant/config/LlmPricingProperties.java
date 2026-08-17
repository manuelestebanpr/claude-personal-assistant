package com.my.custom.claudepersonalassistant.assistant.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Anthropic list prices, in USD per <em>million</em> tokens, keyed by resolved model name and then
 * by token type.
 *
 * <p>Prices are configuration and never code: they change whenever Anthropic changes them, and a
 * price compiled into a class is a price nobody updates. {@code pricing.properties} holds the
 * values and is the only file to edit.
 *
 * <p><strong>The map key is the model name Anthropic returns, not the alias this application
 * requests.</strong> {@code spring.ai.anthropic.chat.model} is set to the moving alias
 * {@code claude-haiku-4-5}, but a live response resolves it to the dated snapshot
 * {@code claude-haiku-4-5-20251001} — verified in {@code AnthropicTokenAccountingVerificationTest},
 * which prints {@code ChatResponse.getMetadata().getModel()}. That resolved name is what
 * {@code DefaultChatModelObservationConvention} puts on the {@code gen_ai.response.model} tag and
 * therefore what the price gauge has to be tagged with for the PromQL join to match. Keying this
 * map by the alias would produce a gauge that joins to nothing and a dashboard of empty panels.
 *
 * @param prices per-model prices; a model absent from this map is reported by
 *               {@link UnpricedModelWarner} rather than silently costed at zero
 */
@ConfigurationProperties("app.llm.pricing")
public record LlmPricingProperties(Map<String, ModelPrices> prices) {

    /** Metric tag values, matching the {@code gen_ai.token.type} values Spring AI already emits. */
    public static final String TOKEN_TYPE_INPUT = "input";
    public static final String TOKEN_TYPE_OUTPUT = "output";
    public static final String TOKEN_TYPE_CACHE_WRITE = "cache_write";
    public static final String TOKEN_TYPE_CACHE_READ = "cache_read";

    public LlmPricingProperties {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    /** {@code true} when the named model has a pricing entry. */
    public boolean isPriced(String model) {
        return model != null && prices.containsKey(model);
    }

    /**
     * USD per million tokens for one model and token type, keyed by the metric tag values above.
     *
     * <p>{@code cacheWrite} and {@code cacheRead} are carried even though prompt caching is
     * currently off in this application ({@code AnthropicCacheOptions} defaults to
     * {@code AnthropicCacheStrategy.NONE} and nothing overrides it), so
     * {@code cache_creation_input_tokens} and {@code cache_read_input_tokens} are always zero.
     * They exist so that turning caching on later is a price edit rather than a code change —
     * and because Anthropic bills them at different rates from {@code input}, so folding them into
     * one number would be wrong the moment caching is enabled.
     *
     * @param input      price of an uncached input token
     * @param output     price of an output token
     * @param cacheWrite price of a {@code cache_creation} input token
     * @param cacheRead  price of a {@code cache_read} input token
     */
    public record ModelPrices(
            @DefaultValue("0.0") double input,
            @DefaultValue("0.0") double output,
            @DefaultValue("0.0") double cacheWrite,
            @DefaultValue("0.0") double cacheRead) {

        /**
         * The four prices keyed by their {@code gen_ai.token.type} tag value, in a stable order so
         * the gauges register deterministically.
         */
        public Map<String, Double> byTokenType() {
            Map<String, Double> byType = new LinkedHashMap<>();
            byType.put(TOKEN_TYPE_INPUT, input);
            byType.put(TOKEN_TYPE_OUTPUT, output);
            byType.put(TOKEN_TYPE_CACHE_WRITE, cacheWrite);
            byType.put(TOKEN_TYPE_CACHE_READ, cacheRead);
            return byType;
        }
    }
}
