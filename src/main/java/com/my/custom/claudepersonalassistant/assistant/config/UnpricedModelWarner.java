package com.my.custom.claudepersonalassistant.assistant.config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Names, once per model, any model that shows up in traffic with no entry in
 * {@link LlmPricingProperties}. Its tokens are still counted — they just cost nothing on the
 * dashboard, which reads exactly like a quiet week.
 *
 * <p>It has to run at response time, not at startup: the tag the cost join matches on is
 * {@code gen_ai.response.model}, and that is the dated snapshot Anthropic resolves the configured
 * alias to. Nothing knows the resolved name until a response comes back, so a startup check against
 * {@code spring.ai.anthropic.chat.model} would compare the wrong string and pass while every panel
 * stayed empty. {@link LlmTokenPriceMeterBinder} covers the one case that <em>is</em> knowable at
 * startup — no prices configured at all.
 *
 * <p>Registered by being a bean: Boot's {@code ObservationAutoConfiguration} adds every
 * {@link ObservationHandler} bean to the {@code ObservationRegistry}, which is the same route
 * Spring AI's own {@code ChatModelMeterObservationHandler} takes.
 *
 * <p>It adds no tag to any meter and no attribute to any span — it only reads. The warned-model set
 * is bounded by the number of distinct models this application ever calls, which is one.
 */
@Component
class UnpricedModelWarner implements ObservationHandler<ChatModelObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(UnpricedModelWarner.class);

    private final LlmPricingProperties pricing;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    UnpricedModelWarner(LlmPricingProperties pricing) {
        this.pricing = pricing;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        String model = responseModel(context);
        if (model == null || pricing.isPriced(model) || !warned.add(model)) {
            return;
        }
        log.atWarn()
                .addKeyValue("responseModel", model)
                .addKeyValue("pricedModels", pricing.prices().keySet())
                .log("Model produced tokens but has no app.llm.pricing.prices entry, so its spend "
                        + "reads as $0.00 on the LLM cost dashboard");
    }

    private String responseModel(ChatModelObservationContext context) {
        ChatResponse response = context.getResponse();
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        String model = response.getMetadata().getModel();
        return StringUtils.hasText(model) ? model : null;
    }
}
