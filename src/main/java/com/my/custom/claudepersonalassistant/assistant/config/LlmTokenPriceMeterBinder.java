package com.my.custom.claudepersonalassistant.assistant.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Publishes the configured prices as one gauge per (model, token type), so Prometheus can turn the
 * free token counts Spring AI already emits into money with a vector join and no exporter, no
 * collector rule and no code that multiplies anything.
 *
 * <p><strong>The tag names are the whole point.</strong> They are {@code gen_ai.response.model} and
 * {@code gen_ai.token.type} — byte-for-byte the tag names {@code DefaultChatModelObservationConvention}
 * and {@code ModelUsageMetricsGenerator} put on {@code gen_ai.client.token.usage}. Micrometer's OTLP
 * registry mangles dots to underscores on the way out, so both meters arrive in Prometheus carrying
 * {@code gen_ai_response_model} and {@code gen_ai_token_type} and
 * {@code * on (gen_ai_response_model, gen_ai_token_type) group_left()} joins them directly. Rename
 * either tag and the join needs a {@code label_replace()} wrapper on every panel.
 *
 * <p>{@code strongReference(true)} is not optional. Micrometer holds a gauge's value source through
 * a {@link java.lang.ref.WeakReference} by default; the supplier below closes over a loop-local
 * model name, so with the default the lambda becomes unreachable as soon as {@code bindTo} returns
 * and the next GC turns every price into {@code NaN}. The series does not disappear — it goes
 * stale, the join drops to no matching series, and the dashboard reads "No data" with nothing in
 * the logs.
 *
 * <p><strong>No per-request identifier is tagged here or anywhere in this change.</strong> Chat id,
 * conversation id and trace id are unbounded and would create one Prometheus series per
 * conversation forever. Per-turn attribution belongs on the {@code gen_ai} span in Tempo, which
 * already carries it.
 */
@Component
class LlmTokenPriceMeterBinder implements MeterBinder {

    static final String PRICE_GAUGE = "llm.token.price.usd.per.mtok";
    static final String TAG_RESPONSE_MODEL = "gen_ai.response.model";
    static final String TAG_TOKEN_TYPE = "gen_ai.token.type";

    private static final String DESCRIPTION = "Configured list price in USD per million tokens";

    private static final Logger log = LoggerFactory.getLogger(LlmTokenPriceMeterBinder.class);

    private final LlmPricingProperties pricing;

    LlmTokenPriceMeterBinder(LlmPricingProperties pricing) {
        this.pricing = pricing;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (pricing.prices().isEmpty()) {
            log.atWarn().log("No app.llm.pricing.prices entries are configured: every cost panel "
                    + "will read No data because the price gauge has no series to join against");
            return;
        }
        pricing.prices().forEach((model, prices) -> prices.byTokenType().keySet()
                .forEach(tokenType -> register(registry, model, tokenType)));
        log.atInfo()
                .addKeyValue("models", pricing.prices().keySet())
                .addKeyValue("gauges", pricing.prices().size() * LlmPricingProperties.ModelPrices
                        .class.getRecordComponents().length)
                .log("Published LLM token prices as gauges");
    }

    /**
     * The supplier resolves the price on every scrape rather than capturing it once, so a rebound
     * {@link LlmPricingProperties} would be picked up without re-registering the meter.
     *
     * <p>Nothing in this application rebinds it, though, and making that cheap is not possible here:
     * {@code @RefreshScope} and {@code /actuator/refresh} come from Spring Cloud Context, which this
     * project does not depend on and — under the standing "do not modify pom.xml" policy — will not.
     * So in practice a price change is: edit {@code pricing.properties}, restart. That is the
     * documented flow in the README, and it is honest rather than a half-working reload.
     */
    private void register(MeterRegistry registry, String model, String tokenType) {
        Gauge.builder(PRICE_GAUGE, () -> priceOf(model, tokenType))
                .tag(TAG_RESPONSE_MODEL, model)
                .tag(TAG_TOKEN_TYPE, tokenType)
                .description(DESCRIPTION)
                .strongReference(true)
                .register(registry);
    }

    private double priceOf(String model, String tokenType) {
        LlmPricingProperties.ModelPrices prices = pricing.prices().get(model);
        return prices == null ? 0.0 : prices.byTokenType().getOrDefault(tokenType, 0.0);
    }
}
