package com.my.custom.claudepersonalassistant.assistant.config;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Binding of {@code app.llm.pricing}, including the two things about it that are easy to get
 * silently wrong: the bracketed map key and the kebab-cased cache token types.
 */
class LlmPricingPropertiesTest {

    private static final String MODEL = "claude-haiku-4-5-20251001";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(LlmCostConfiguration.class);

    @Test
    void bindsPricesKeyedByResolvedModelNameAndTokenType() {
        runner.withPropertyValues(
                        "app.llm.pricing.prices[" + MODEL + "].input=1.0",
                        "app.llm.pricing.prices[" + MODEL + "].output=5.0",
                        "app.llm.pricing.prices[" + MODEL + "].cache-write=1.25",
                        "app.llm.pricing.prices[" + MODEL + "].cache-read=0.1")
                .run(context -> {
                    LlmPricingProperties pricing = context.getBean(LlmPricingProperties.class);

                    assertThat(pricing.prices()).containsOnlyKeys(MODEL);
                    LlmPricingProperties.ModelPrices prices = pricing.prices().get(MODEL);
                    assertThat(prices.input()).isEqualTo(1.0, within(1e-9));
                    assertThat(prices.output()).isEqualTo(5.0, within(1e-9));
                    assertThat(prices.cacheWrite()).isEqualTo(1.25, within(1e-9));
                    assertThat(prices.cacheRead()).isEqualTo(0.1, within(1e-9));
                });
    }

    /**
     * The bracketed key has to round-trip verbatim. An unbracketed key goes through the relaxed
     * binder, and a model id that came back normalised would tag the gauge with a string the
     * {@code gen_ai_response_model} label never contains — a join that silently matches nothing.
     */
    @Test
    void bracketedModelKeyRoundTripsVerbatim() {
        runner.withPropertyValues("app.llm.pricing.prices[" + MODEL + "].input=1.0")
                .run(context -> assertThat(context.getBean(LlmPricingProperties.class).prices())
                        .containsOnlyKeys(MODEL));
    }

    /** Underscored token types, as written in Anthropic's own pricing table, bind too. */
    @Test
    void acceptsUnderscoredCacheTokenTypeNames() {
        runner.withPropertyValues(
                        "app.llm.pricing.prices[" + MODEL + "].cache_write=1.25",
                        "app.llm.pricing.prices[" + MODEL + "].cache_read=0.1")
                .run(context -> {
                    LlmPricingProperties.ModelPrices prices =
                            context.getBean(LlmPricingProperties.class).prices().get(MODEL);
                    assertThat(prices.cacheWrite()).isEqualTo(1.25, within(1e-9));
                    assertThat(prices.cacheRead()).isEqualTo(0.1, within(1e-9));
                });
    }

    /** An omitted price is 0.0, never null: the gauge must always have a number to publish. */
    @Test
    void omittedPricesDefaultToZeroRatherThanNull() {
        runner.withPropertyValues("app.llm.pricing.prices[" + MODEL + "].input=1.0")
                .run(context -> {
                    LlmPricingProperties.ModelPrices prices =
                            context.getBean(LlmPricingProperties.class).prices().get(MODEL);
                    assertThat(prices.output()).isZero();
                    assertThat(prices.cacheWrite()).isZero();
                    assertThat(prices.cacheRead()).isZero();
                });
    }

    @Test
    void bindsAnEmptyMapWhenNothingIsConfigured() {
        runner.run(context -> assertThat(context.getBean(LlmPricingProperties.class).prices()).isEmpty());
    }

    @Test
    void byTokenTypeIsKeyedByTheMetricTagValues() {
        assertThat(new LlmPricingProperties.ModelPrices(1.0, 5.0, 1.25, 0.1).byTokenType())
                .containsExactly(
                        org.assertj.core.api.Assertions.entry("input", 1.0),
                        org.assertj.core.api.Assertions.entry("output", 5.0),
                        org.assertj.core.api.Assertions.entry("cache_write", 1.25),
                        org.assertj.core.api.Assertions.entry("cache_read", 0.1));
    }

    @Test
    void isPricedAnswersForTheResolvedModelName() {
        runner.withPropertyValues("app.llm.pricing.prices[" + MODEL + "].input=1.0")
                .run(context -> {
                    LlmPricingProperties pricing = context.getBean(LlmPricingProperties.class);

                    assertThat(pricing.isPriced(MODEL)).isTrue();
                    assertThat(pricing.isPriced("claude-haiku-4-5")).isFalse();
                    assertThat(pricing.isPriced(null)).isFalse();
                });
    }
}
