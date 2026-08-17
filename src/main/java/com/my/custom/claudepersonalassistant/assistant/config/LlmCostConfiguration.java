package com.my.custom.claudepersonalassistant.assistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the cost-visibility half of the assistant module: the prices themselves.
 *
 * <p>{@link LlmTokenPriceMeterBinder} and {@link UnpricedModelWarner} are {@code @Component}s picked
 * up by the ordinary scan; only the properties record needs enabling.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmPricingProperties.class)
class LlmCostConfiguration {
}
