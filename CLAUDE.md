# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Server-rendered, multi-conversation chat backed by Claude (Anthropic) via Spring AI. Spring Boot 4.1 / Java 25, WebMVC + Thymeleaf, token-by-token streaming over the servlet `OutputStream`, per-chat history in file-based H2, a built-in MCP server for tools, OpenTelemetry export to a Grafana LGTM stack. Single user — no auth, no user management.

`README.md` is the long-form reference (streaming protocol, error-classification matrix, observability, running instructions). This file carries what is needed to change code correctly.

## Build and test commands

Use the Maven wrapper:
- Build: `./mvnw clean install` (compile only: `./mvnw compile`)
- All tests: `./mvnw test`
- Single class: `./mvnw test -Dtest=ChatModuleIntegrationTests`
- Single method: `./mvnw test -Dtest=ChatModuleIntegrationTests#happyPathStreamPersistsBothMessagesAndRehydratesOnReopen`
- Run with the observability stack (Testcontainers `grafana/otel-lgtm`, needs Docker/podman): `./mvnw spring-boot:test-run` — boots via `TestClaudePersonalAssistantApplication`, not the main class
- Run plain (no containers, OTLP export warnings are harmless): `./mvnw spring-boot:run`
- Production-style: `podman compose up --build` (Grafana on `:3000`, app on `:8080`, H2 file in `./data`, telemetry in the `lgtm-data` volume). podman-compose does not always recreate a running container when the image changes — run `podman compose down` first if a change seems not to take effect.
- The default JDK on this machine is 21; the build needs 25. Prefix commands with `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-graal` if `./mvnw` reports `release version 25 not supported` (check `ls ~/.sdkman/candidates/java` — there is no `-tem` 25 installed).

`ANTHROPIC_API_KEY` comes from a git-ignored `.env` (see `.env.example`), imported through `spring.config.import=optional:file:.env[.properties]`. Tests use a dummy key from `src/test/resources/application.properties`.

Tests that boot the full context (`ClaudePersonalAssistantApplicationTests`) require a container runtime. Slice tests (`@WebMvcTest`) and `@ApplicationModuleTest` tests do not.

## Architecture

Four Spring Modulith modules, direct subpackages of `com.my.custom.claudepersonalassistant`:

```
audit  ──▶ chat ──▶ assistant ──▶ Spring AI / Anthropic
  │          │
  └──────────┴──▶ mcp ──▶ (its own HTTP endpoint, over loopback)
```

- **`chat`** — conversation lifecycle, persistence, web UI, streaming endpoint, tool palette.
- **`assistant`** — Spring AI / Anthropic integration. Depends on **nothing**; Spring AI types never cross `AssistantClient`.
- **`mcp`** — MCP server (revision `2026-07-28`) plus the outbound port to reach one. Depends on **nothing**, so it can be lifted into its own service.
- **`audit`** — observability listeners consuming events from the other three, plus the Logback → OpenTelemetry bridge.

**Package convention — named interfaces, not root-level API.** Each module publishes an explicit surface through `@NamedInterface` packages (`api`, `dto`, `event`, `exception`); everything else (`web`, `service`, `persistence`, `config`, `client`, `error`, `logging`, `domain`, `protocol`) is internal and unreachable from outside. Module roots hold only `package-info.java`.

`allowedDependencies` therefore names interfaces, not modules:

| Module | May depend on |
|---|---|
| `chat` | `assistant::api`, `assistant::dto`, `assistant::exception`, `mcp::api` |
| `audit` | `chat::event`, `assistant::event`, `assistant::dto`, `mcp::event` |
| `assistant`, `mcp` | nothing (`allowedDependencies = {}`) |

`ModularityTests` enforces this with `ApplicationModules.verify()` plus an assertion pinning the module set to exactly `{assistant, audit, chat, mcp}`. **A boundary violation fails the build — it is not a convention.** An `audit` class touching `chat::api` fails with *"Module 'audit' depends on named interface(s) 'chat :: api' … Allowed targets: chat :: event, …"*. When adding a type other modules must see, put it in a named-interface package and widen `allowedDependencies` deliberately; do not move it to the module root.

**Layering inside `chat`**: controller → `ChatFacade` (interface) → `ConversationService` / `MessageService` (interfaces) → Spring Data repositories. Constructor injection everywhere, records for DTOs and events, request paths and view/model names as constants on the controllers, tunables in `ChatProperties`.

### The boundary translation is deliberate

`assistant.HistoryMessage(HistoryRole, String)` and `chat.persistence.MessageEntity` look redundant but are the two sides of the port. `HistoryMessage` is the model contract — role plus text, nothing else. `MessageEntity` carries id, FK, timestamp, `@Lob` content. `DefaultChatFacade.toHistory()` maps between them; that mapping is what keeps JPA out of `assistant` and Spring AI out of `chat`. Do not "simplify" it by sharing a type across the boundary.

### Streaming turn lifecycle

`ChatFacade.prepareTurn()` splits the turn in two on purpose:

1. **Synchronous, before the response body starts**: validate the chat exists (so a missing chat still yields a 404), read the context window **before** appending the user message, persist the user message, derive the title from the first message.
2. **Returned `ChatTurn`**: the slow assistant call. `ChatStreamController` drives it inside a `StreamingResponseBody`, writing one NDJSON line per `StreamEvent` and flushing after each.

`DefaultChatFacade.streamAnswer` accumulates deltas and persists the answer in a `finally` block — **full on completion, partial on error or client disconnect** — so a reload always matches what the user saw. `ChatClientAssistant` consumes `Flux.toStream()` in a try-with-resources so a client disconnect cancels the upstream Anthropic HTTP stream.

Once streaming starts the HTTP status is committed, so mid-stream errors are part of the protocol, not a status code: the client receives an `ERROR` line with a `RETRYABLE`/`TERMINAL`/`UNKNOWN` classification and keeps any partial text.

### Memory design — no Spring AI memory advisor

The JPA message store is the single source of truth; the facade rebuilds model context manually every turn. This is not an oversight: `MessageWindowChatMemory.add()` calls `ChatMemoryRepository.saveAll()` with the *windowed* list under replace-all semantics, so backing it with the history table would truncate persisted history to the window size. `chat.context-window-size=0` (default) replays full history; a positive value caps to the last N messages.

### MCP module — why the protocol is hand-written

`mcp` speaks MCP revision **`2026-07-28`**, which removed the `initialize` handshake,
`notifications/initialized`, protocol sessions (`Mcp-Session-Id`), the standalone GET/SSE
stream and `Last-Event-ID` resumption. Every message is a self-contained POST to `/mcp`
carrying its own `_meta.io.modelcontextprotocol/*`, mirrored into the `MCP-Protocol-Version`,
`Mcp-Method` and `Mcp-Name` headers.

**Do not replace this with Spring AI's MCP starter.** It was evaluated and rejected: the MCP
Java SDK tracks `2025-11-25`, where `spring.ai.mcp.server.protocol=STATELESS` only drops the
session id and keeps the handshake on the wire — the opposite of what this module is for.
Implementing the newer revision directly also costs no dependencies (plain Spring MVC +
Jackson 3).

Rules the endpoint must keep:
- Header and body must agree, or reject with `400` + `-32020`. A proxy routing on the header
  while the server executes the body is the vulnerability this closes.
- Malformed request → JSON-RPC error. A tool that *ran and failed* → normal result with
  `isError: true`, so the model can read the reason and self-correct.
- `GET`/`DELETE /mcp` → `405`; an `Mcp-Session-Id` is ignored, never echoed.
- `tools/list` must be deterministically ordered (clients cache it; model prompt caches
  depend on it). `DefaultToolRegistry` sorts by name and rejects duplicates at startup.

Adding a tool = one `@Component` implementing `McpTool`. Nothing else changes.

`chat` reaches the server through `McpToolGateway` over **real HTTP loopback**
(`mcp.client.base-url`, default `http://localhost:8080`), not in-process — extracting the
server later is a property change. Build its `RestClient` with `RestClient.builder()`, not an
injected `RestClient.Builder`: `spring-boot-starter-webmvc` brings only the server side, so no
such bean exists and asking for one stops the whole context from starting.

### Logging to OpenTelemetry is hand-written too

Boot auto-configures an `SdkLoggerProvider`, a batch processor and an OTLP log exporter — and
installs **no Logback appender**, so log export looks configured and exports nothing. The
documented remedy, `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, has
**no released version** (61 published, every one `-alpha`), so `audit/logging/` carries a small
appender written against `io.opentelemetry.api.logs` — GA since 1.27.0, compile-scope already,
and documented as the API for exactly this.

`OpenTelemetryLogRecordAppender` **must keep mapping `event.getKeyValuePairs()`**: the whole
application logs structured facts through `log.atInfo().addKeyValue(...)`, so dropping them
leaves Loki with message bodies and nothing to query on. `OpenTelemetryLoggingBridge` attaches
it to the root logger programmatically (no `logback-spring.xml`), which is why records logged
before the context is ready are not exported.

## Constraints that will bite

- **Do not modify `pom.xml`** — standing project policy, still intact. The unused `spring-ai-spring-boot-testcontainers` dependency and the empty Initializr `<licenses>`/`<developers>`/`<scm>` placeholders stay as they are. Both the MCP module and the OTLP log bridge were built to hold this line; neither needs a dependency. **Never introduce an alpha/beta/snapshot artifact** — check the registry for a released line first, and prefer a GA API already on the classpath over a pre-release one.
- **Jackson 3**: Boot 4 auto-configures `tools.jackson.databind.ObjectMapper`. There is **no** Jackson 2 `ObjectMapper` bean. Annotations still come from `com.fasterxml.jackson.annotation` (see `StreamEvent`).
- **This is not WebFlux.** `Flux` appears only where Spring AI hands one back, inside `ChatClientAssistant`. Everything runs on Servlet MVC with virtual threads (`spring.threads.virtual.enabled=true`, `spring.mvc.async.request-timeout=5m`). Do not re-expose reactive types.
- **`spring.jpa.hibernate.ddl-auto=update` is mandatory**: Boot does not treat file-based H2 as "embedded", so the default would be `none`; `update` also creates Modulith's `EVENT_PUBLICATION` table.
- **Never add `AUTO_SERVER=TRUE` to the H2 URL.** H2 2.x throws `Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"` while opening the database — mixed mode needs the JVM shutdown hook that `DB_CLOSE_ON_EXIT=FALSE` disables. Keep `DB_CLOSE_ON_EXIT=FALSE` (Hikari and Boot own the connection lifecycle); the `/h2-console` is in-process and never needs server mode.
- **`spring.ai.retry.*` does not apply to Anthropic in Spring AI 2.0.** Retries are SDK-internal via `spring.ai.anthropic.max-retries`. There is no app-level retry.
- **Anthropic SDK exceptions propagate unwrapped**, so `AnthropicErrorClassifier` walks the cause chain to classify them.
- **Publishing an event from a non-transactional thread needs an explicit transaction.** `@ApplicationModuleListener` is `AFTER_COMMIT`, so a publish with no transaction in scope is **silently dropped**: the registry row is written, the listener never runs, and the only symptom is a "Marking stale publications … as failed" line a minute later. `AssistantErrorPublisher` (streaming catch block on a virtual thread) and `ToolEventPublisher` (tools invoked straight from the HTTP endpoint) both wrap the publish in a `REQUIRES_NEW` `TransactionTemplate` for this reason. Any new publisher outside a `@Transactional` service needs the same.
- **`Scenario` hides that bug.** `Scenario.stimulate(Runnable)` runs its stimulus inside a transaction, so a test written that way passes with or without the wrapper. `McpModuleEventTests` calls the service directly and asserts on the *auditor's counter* — the registry row exists either way, but the counter only moves if the listener actually ran.
- **Telemetry config that silently produces an empty Grafana**: `management.tracing.sampling.probability` defaults to `0.1` (set to `1.0` here); timers publish a single `+Inf` bucket unless `management.metrics.distribution.percentiles-histogram.*` asks (measured: 69 buckets with it, 1 without — `histogram_quantile` over one bucket is not empty, it is meaningless), so every p95 panel would read nonsense; and `otel-lgtm` keeps Grafana/Loki/Prometheus/Tempo under `/data`, so without the `lgtm-data` volume every restart wipes the lot.
- **`src/test/resources/application.properties` shadows the main one, it does not merge.** `target/test-classes` precedes `target/classes` and Boot resolves `classpath:/application.properties` to the first match only, so `spring-boot:test-run` (test classpath) sees *only* the test file. That is why the shared telemetry tuning lives in `src/main/resources/observability.properties`, imported by both files, and why `TestClaudePersonalAssistantApplication` passes `--management.*.export.enabled=true` and the 5m async timeout as command-line args. Anything added to the main `application.properties` that the dev run also needs must go in `observability.properties` or that override list, or it will apply in production and nowhere else.
- **`management.otlp.logging.export.enabled` is deprecated at `level=error` in Boot 4** — it no longer binds, so it reads as "log export disabled" while logs keep exporting. The property that works is `management.logging.export.otlp.enabled`.
- **A container clock behind the host silently drops every metric sample.** Samples carry the JVM clock; Prometheus in a lagging podman VM rejects them as too far in the future. Signature: `otelcol_exporter_send_failed_metric_points_total` rising exactly in step with `otelcol_receiver_accepted_metric_points_total`, while traces and logs still work. Repair with `podman machine ssh <machine> "sudo date -s @$(date +%s)"` — it is not a config problem.
- **Per-module metrics are explicit, not `@Timed`.** The annotation needs an AOP starter and the pom is frozen. Every module times its boundary as `app.module.operation{module,operation,outcome}` and owns its own constants class — a module importing another's metric names would be depending on it.
- **`spring-boot-docker-compose` is deliberately absent**: it shells out to a literal `docker` binary, which breaks under podman.
- Tests deliberately declare no `spring.datasource.url`, so each test context gets its own in-memory H2 and concurrently cached contexts cannot drop each other's tables. The blind spot that creates — a broken production URL passing the whole suite — is covered by `ProductionDatasourceUrlTest`, which opens the real URL relocated to a temp directory.

## Events

| Event | Published by | Consumed by |
|---|---|---|
| `ChatCreatedEvent` | `DefaultConversationService.create()` | `ChatLifecycleAuditor` → `assistant.chats.created` |
| `ChatDeletedEvent` | `DefaultConversationService.delete()` | `ChatLifecycleAuditor` → `assistant.chats.deleted` |
| `AssistantErrorEvent` | `AssistantErrorPublisher` | `AssistantErrorAuditor` → `assistant.stream.errors{classification,error.type}` |
| `ToolInvokedEvent` | `ToolEventPublisher` | `McpToolAuditor` → `assistant.tools.invoked{tool,outcome}` |
| `ToolInvocationRejectedEvent` | `ToolEventPublisher` | `McpToolAuditor` → `assistant.tools.rejected{tool}` |
