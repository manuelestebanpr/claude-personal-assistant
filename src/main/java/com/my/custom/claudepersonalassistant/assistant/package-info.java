/**
 * Spring AI / Anthropic integration. Depends on no other application module — enforced by
 * {@link org.springframework.modulith.ApplicationModule#allowedDependencies()} so a future
 * import from {@code chat} or {@code audit} fails {@code ApplicationModules.verify()} instead
 * of only being caught by convention.
 *
 * <p>What other modules may use is declared package by package as
 * {@link org.springframework.modulith.NamedInterface named interfaces} — {@code api},
 * {@code dto}, {@code event} and {@code exception}. Everything else ({@code client},
 * {@code config}, {@code error}, {@code logging}) is internal and unreachable from outside.
 */
@ApplicationModule(allowedDependencies = {})
package com.my.custom.claudepersonalassistant.assistant;

import org.springframework.modulith.ApplicationModule;
