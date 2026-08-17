package com.my.custom.claudepersonalassistant.assistant.config;

import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.observation.conventions.AiObservationAttributes;
import org.springframework.ai.observation.conventions.AiObservationMetricAttributes;
import org.springframework.ai.observation.conventions.AiObservationMetricNames;
import org.springframework.ai.observation.conventions.AiTokenType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The gauge only has value if it joins to Spring AI's token counter, and it only joins if the tag
 * names match exactly — so the assertions here compare against Spring AI's own constants rather
 * than against string literals copied from them. A library rename fails this test instead of
 * quietly emptying the dashboard.
 */
class LlmTokenPriceMeterBinderTest {

    private static final String MODEL = "claude-haiku-4-5-20251001";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void registersOneGaugePerModelAndTokenType() {
        bind(pricing(1.0, 5.0, 1.25, 0.1));

        List<Gauge> gauges = gauges();

        assertThat(gauges).hasSize(4);
        assertThat(gauges).extracting(gauge -> gauge.getId().getTag(LlmTokenPriceMeterBinder.TAG_TOKEN_TYPE))
                .containsExactlyInAnyOrder("input", "output", "cache_write", "cache_read");
        assertThat(gauges).allSatisfy(gauge -> assertThat(
                gauge.getId().getTag(LlmTokenPriceMeterBinder.TAG_RESPONSE_MODEL)).isEqualTo(MODEL));
    }

    @Test
    void gaugeValuesAreTheConfiguredPricesPerMillionTokens() {
        bind(pricing(1.0, 5.0, 1.25, 0.1));

        assertThat(priceOf("input")).isEqualTo(1.0, within(1e-9));
        assertThat(priceOf("output")).isEqualTo(5.0, within(1e-9));
        assertThat(priceOf("cache_write")).isEqualTo(1.25, within(1e-9));
        assertThat(priceOf("cache_read")).isEqualTo(0.1, within(1e-9));
    }

    /**
     * The meter name and both tag names must survive Micrometer's dot-to-underscore mangling into
     * exactly the labels {@code gen_ai_client_token_usage_total} carries, or the PromQL join needs
     * a {@code label_replace()} on every panel.
     */
    @Test
    void tagNamesMatchTheTagsSpringAiPutsOnTheTokenUsageCounter() {
        assertThat(LlmTokenPriceMeterBinder.TAG_RESPONSE_MODEL)
                .isEqualTo(AiObservationAttributes.RESPONSE_MODEL.value())
                .isEqualTo("gen_ai.response.model");
        assertThat(LlmTokenPriceMeterBinder.TAG_TOKEN_TYPE)
                .isEqualTo(AiObservationMetricAttributes.TOKEN_TYPE.value())
                .isEqualTo("gen_ai.token.type");
        assertThat(AiObservationMetricNames.TOKEN_USAGE.value()).isEqualTo("gen_ai.client.token.usage");
    }

    /** The two token types the counter actually emits must be spellable from the pricing config. */
    @Test
    void tokenTypeTagValuesMatchTheOnesTheCounterEmits() {
        assertThat(LlmPricingProperties.TOKEN_TYPE_INPUT).isEqualTo(AiTokenType.INPUT.value());
        assertThat(LlmPricingProperties.TOKEN_TYPE_OUTPUT).isEqualTo(AiTokenType.OUTPUT.value());
    }

    /**
     * Micrometer holds a gauge's value source weakly by default and the supplier closes over a
     * loop-local, so without {@code strongReference(true)} every price reads NaN after the first GC
     * — a stale series, not a missing one, which is why it would never show up as an error.
     */
    @Test
    void gaugeSurvivesGarbageCollection() {
        bind(pricing(1.0, 5.0, 1.25, 0.1));

        System.gc();
        System.gc();

        assertThat(priceOf("input")).isNotNaN().isEqualTo(1.0, within(1e-9));
    }

    /** No prices configured is a loud condition, not four zero gauges pretending to be data. */
    @Test
    void registersNothingWhenNoPricesAreConfigured() {
        bind(new LlmPricingProperties(Map.of()));

        assertThat(gauges()).isEmpty();
    }

    /** A model with no entry gets no gauge, so the join drops it rather than costing it at zero. */
    @Test
    void unknownModelHasNoPriceSeries() {
        bind(pricing(1.0, 5.0, 1.25, 0.1));

        assertThat(gauges()).extracting(gauge -> gauge.getId().getTag(LlmTokenPriceMeterBinder.TAG_RESPONSE_MODEL))
                .doesNotContain("claude-opus-5");
    }

    private void bind(LlmPricingProperties pricing) {
        new LlmTokenPriceMeterBinder(pricing).bindTo(registry);
    }

    private LlmPricingProperties pricing(double input, double output, double cacheWrite, double cacheRead) {
        return new LlmPricingProperties(Map.of(MODEL,
                new LlmPricingProperties.ModelPrices(input, output, cacheWrite, cacheRead)));
    }

    private List<Gauge> gauges() {
        return List.copyOf(registry.find(LlmTokenPriceMeterBinder.PRICE_GAUGE).gauges());
    }

    private double priceOf(String tokenType) {
        return registry.get(LlmTokenPriceMeterBinder.PRICE_GAUGE)
                .tags(LlmTokenPriceMeterBinder.TAG_RESPONSE_MODEL, MODEL,
                        LlmTokenPriceMeterBinder.TAG_TOKEN_TYPE, tokenType)
                .gauge()
                .value();
    }
}
