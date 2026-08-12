/**
 * Spring AI / Anthropic integration. Depends on no other application module — enforced by
 * {@link org.springframework.modulith.ApplicationModule#allowedDependencies()} so a future
 * import from {@code chat} or {@code audit} fails {@code ApplicationModules.verify()} instead
 * of only being caught by convention.
 */
@ApplicationModule(allowedDependencies = {})
package com.my.custom.claudepersonalassistant.assistant;

import org.springframework.modulith.ApplicationModule;
