/**
 * Observability: listeners that turn the events published by {@code chat} and {@code assistant}
 * into structured logs and metrics, plus the Logback-to-OpenTelemetry bridge that makes log
 * export happen at all.
 *
 * <p>Allowed to depend only on the {@code event} interfaces of the publishing modules (and the
 * {@code assistant} value types those events carry). It can no longer reach anything else those
 * modules expose, which is what keeps auditing a one-way, event-driven concern.
 */
@ApplicationModule(allowedDependencies = {
        "chat::event",
        "assistant::event",
        "assistant::dto",
        "mcp::event" })
package com.my.custom.claudepersonalassistant.audit;

import org.springframework.modulith.ApplicationModule;
