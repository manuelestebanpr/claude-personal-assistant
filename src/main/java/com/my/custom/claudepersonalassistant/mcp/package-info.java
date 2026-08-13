/**
 * A Model Context Protocol server, and the outbound port other modules use to reach it.
 *
 * <p>Speaks MCP revision {@code 2026-07-28}, which removed the {@code initialize} handshake,
 * {@code notifications/initialized}, protocol sessions and the standalone SSE stream: every
 * message is a self-contained POST to a single endpoint. Spring AI's MCP starter could not be
 * used because the MCP Java SDK still tracks {@code 2025-11-25}, where its {@code STATELESS}
 * mode drops the session id but keeps the handshake on the wire.
 *
 * <p>Depends on no other application module, so it can be lifted out into a service of its own
 * without touching anything else. What it publishes is declared as named interfaces: {@code api}
 * for the port callers use and {@code event} for what {@code audit} observes.
 */
@ApplicationModule(allowedDependencies = {})
package com.my.custom.claudepersonalassistant.mcp;

import org.springframework.modulith.ApplicationModule;
