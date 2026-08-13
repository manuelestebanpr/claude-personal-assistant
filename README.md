# claude-personal-assistant

A server-rendered, multi-conversation chat backed by Claude (Anthropic) via Spring AI, with
token-by-token streaming over the servlet OutputStream, persistent per-chat history in
file-based H2, and full observability (traces, metrics, logs) in a Grafana LGTM stack.
Single user — no auth or user management.

- Spring Boot 4.1 (Java 25), WebMVC + Thymeleaf (no CDN assets — plain CSS/JS)
- Spring AI 2.0 Anthropic (`claude-haiku-4-5`, max-tokens 4000, temperature 0.5)
- Spring Data JPA + H2 (file-based, survives restarts)
- Spring Modulith 2.1 (module structure, named interfaces, event publication registry)
- A built-in MCP server speaking the stateless **2026-07-28** revision, reached over loopback
- OpenTelemetry export to Grafana LGTM (dev via Testcontainers, prod via compose)

## Module architecture (Spring Modulith)

Direct subpackages of `com.my.custom.claudepersonalassistant` are the modules. Each publishes
an explicit surface through `@NamedInterface` packages; everything else is internal.

```
chat       conversation lifecycle, persistence, web UI, streaming endpoint, tool palette
           api/ ChatFacade, ChatTurn
           dto/ ConversationDto, ChatMessageDto, ConversationView, MessageRole,
                StreamEvent, ToolDto
           event/ ChatCreatedEvent, ChatDeletedEvent
           internal: config/ service/ persistence/ web/
assistant  Spring AI / Anthropic integration
           api/ AssistantClient
           dto/ AssistantRequest, HistoryMessage, HistoryRole, ClassifiedError,
                ErrorClassification
           event/ AssistantErrorEvent          exception/ AssistantException
           internal: client/ config/ error/ logging/
mcp        MCP server (revision 2026-07-28) and the outbound port to reach one
           api/ McpToolGateway, ToolDescriptor, ToolInvocation, ToolResult, McpClientException
           event/ ToolInvokedEvent, ToolInvocationRejectedEvent
           internal: domain/ domain/tool/ protocol/ web/ client/ config/
audit      observability listeners plus the Logback → OpenTelemetry bridge
           ChatLifecycleAuditor, AssistantErrorAuditor, McpToolAuditor, AuditMetrics,
           logging/ OpenTelemetryLogRecordAppender, OpenTelemetryLoggingBridge
```

Dependencies are declared against named interfaces, not whole modules:

| Module | May depend on |
|---|---|
| `chat` | `assistant::api`, `assistant::dto`, `assistant::exception`, `mcp::api` |
| `audit` | `chat::event`, `assistant::event`, `assistant::dto`, `mcp::event` |
| `assistant`, `mcp` | nothing |

So `audit` can see the events the other modules publish and nothing else — reaching for
`chat::api` fails the build with *"Module 'audit' depends on named interface(s) 'chat :: api'
… Allowed targets: chat :: event, …"*. Spring AI types never leave `assistant`, and MCP wire
types never leave `mcp`. `ModularityTests` enforces all of it via `ApplicationModules.verify()`.

Layering inside `chat`: controller → `ChatFacade` (interface) → `ConversationService` /
`MessageService` (interfaces) → Spring Data repositories. Constructor injection everywhere,
records for DTOs/events, mapping paths and view/model names as constants on the controllers,
tunables in `ChatProperties`.

### Event flow

| Event | Published by | Listener (audit) | Output |
|---|---|---|---|
| `ChatCreatedEvent` | `DefaultConversationService.create()` (inside `@Transactional`) | `ChatLifecycleAuditor` | INFO log + counter `assistant.chats.created` |
| `ChatDeletedEvent` | `DefaultConversationService.delete()` | `ChatLifecycleAuditor` | INFO log + counter `assistant.chats.deleted` |
| `AssistantErrorEvent` | `AssistantErrorPublisher` (wrapped in a `REQUIRES_NEW` `TransactionTemplate`: the publish happens in a blocking catch block on a virtual thread with no bound transaction, and `@ApplicationModuleListener` is AFTER_COMMIT, so the event would otherwise be dropped) | `AssistantErrorAuditor` | ERROR log + counter `assistant.stream.errors{classification,error.type}` |
| `ToolInvokedEvent` | `ToolEventPublisher` (same `REQUIRES_NEW` wrapper, same reason: tools are invoked straight from the HTTP endpoint with no transaction in scope) | `McpToolAuditor` | INFO log + counter `assistant.tools.invoked{tool,outcome}` |
| `ToolInvocationRejectedEvent` | `ToolEventPublisher` | `McpToolAuditor` | WARN log + counter `assistant.tools.rejected{tool}` |

## How streaming works

1. The browser POSTs `{"content": "..."}` to `/chats/{chatId}/messages/stream`.
2. `ChatStreamController` calls `ChatFacade.prepareTurn(...)`, which does the fast half of the
   turn synchronously — validate the chat (404 before the body starts), read the history
   window *before* saving, save the user message, derive the title from the first message —
   and returns a `ChatTurn` holding only the slow assistant call.
3. The controller drives that `ChatTurn` inside a `StreamingResponseBody`, writing one NDJSON
   line per event and flushing after each. Virtual threads
   (`spring.threads.virtual.enabled=true`) make the blocking iteration cheap;
   `spring.mvc.async.request-timeout=5m` gives generation headroom.
   `DefaultChatFacade.streamAnswer` bridges `AssistantClient.stream(...)` into `StreamEvent`s
   and persists the streamed answer in a `finally` block — full on completion, partial on
   error or client disconnect — so a reload always matches what the user saw.
4. `ChatClientAssistant` replays history as user/assistant messages plus the shared system
   prompt (the only cross-chat context) and streams `ChatResponse` chunks, filtering to text
   deltas. It consumes the Spring AI `Flux` via `toStream()` in a try-with-resources, so a
   client disconnect propagates out of the loop, closes the `Stream`, and cancels the upstream
   Anthropic HTTP stream.
5. The browser consumes the response with `fetch()` + `ReadableStream`, splitting on
   newlines and JSON-parsing each line.

NDJSON protocol (one JSON object per line):

```
{"type":"DELTA","content":"Hello"}
{"type":"ERROR","classification":"RETRYABLE","message":"..."}
{"type":"DONE"}
```

Why HTTP 200 even on mid-stream errors: once streaming starts the status line and headers
are already committed — that is inherent to HTTP streaming. Errors are therefore part of the
protocol: the client keeps any partial text, renders the ERROR line, and shows a retry hint
when the classification is `RETRYABLE`.

## Memory design

There is **no Spring AI memory advisor**. The JPA message store is the single source of
truth; the facade rebuilds model context manually (history window + new user text) on every
turn. Reason: `MessageWindowChatMemory.add()` calls `ChatMemoryRepository.saveAll()` with the
*windowed* list, and that contract is replace-all — backing it with the history table would
truncate persisted history to the window size. Manual context also keeps explicit DAO
reads/writes, matching the required layering.

`chat.context-window-size` controls how much history is replayed: `0` (default) replays the
full chat history (Claude Haiku has a 200K context window); a positive value caps to the last
N messages.

## Error classification

The Anthropic SDK's exceptions (`com.anthropic.errors.*`) propagate unwrapped in Spring AI
2.0. After the SDK's internal retries (`spring.ai.anthropic.max-retries=3`) are exhausted,
`AnthropicErrorClassifier` walks the cause chain:

| Classification | Errors |
|---|---|
| `RETRYABLE` | `RateLimitException` (429), `InternalServerException` (5xx incl. 529 `overloaded_error`), `AnthropicIoException` (network), `AnthropicRetryableException`, other `AnthropicServiceException` with status ≥ 500 or 429 |
| `TERMINAL` | `BadRequestException` (400), `UnauthorizedException` (401), `PermissionDeniedException` (403), `NotFoundException` (404), `UnprocessableEntityException` (422), other `AnthropicServiceException` with any other status |
| `UNKNOWN` | anything else |

There is no app-level retry: `spring.ai.retry.*` does not apply to the Anthropic module in
Spring AI 2.0.

## Observability

Everything (traces, metrics, logs) is pushed over OTLP to a Grafana LGTM stack. Three things
are worth knowing, because each one silently produces an empty Grafana if you get it wrong.

**Boot ships no Logback appender.** It auto-configures an `SdkLoggerProvider`, a batch
processor and an OTLP log exporter — and then nothing feeds them, so log export appears
configured and exports nothing. The documented remedy,
`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, has **no released
version** (61 published, every one `-alpha`). So `audit/logging/` carries a small appender
written against `io.opentelemetry.api.logs`, GA since 1.27.0 and documented as the API for
exactly this, plus a bridge that attaches it to the root logger once the context is up.

It maps `event.getKeyValuePairs()` onto attributes, which matters more than it sounds: this
application logs structured facts through `log.atInfo().addKeyValue(...)` everywhere, so
dropping them would leave Loki with message bodies and nothing to query on. A line arrives
carrying `chatId`, `title`, `blockType`, `finishReason` and the current `trace_id`. Records
logged before the Spring context is ready are not exported; console output is unaffected.

**Traces are sampled at 10% by default.** `management.tracing.sampling.probability=1.0` —
on a single-user app the default makes Tempo look broken.

**otel-lgtm keeps everything under `/data`.** Without a volume, every restart wipes Grafana,
Loki, Prometheus and Tempo at once. `compose.yaml` mounts a named `lgtm-data` volume, and
gates the app on `condition: service_healthy` so it cannot start pushing before the collector
is listening.

### Where the data goes

- **Dev/test**: `./mvnw spring-boot:test-run` boots the app with a Testcontainers-managed
  `grafana/otel-lgtm` container wired via `@ServiceConnection` — no compose file needed.
- **Prod**: `compose.yaml` runs the LGTM stack next to the app; Boot 4.1 maps the native
  `OTEL_EXPORTER_OTLP_*` env vars onto its own properties, appending the per-signal path.
- Grafana: `http://localhost:3000` (compose) or the mapped port Testcontainers logs.

### Dashboards

Two dashboards are provisioned from `observability/grafana/`, alongside the ones the image
ships itself:

- **JVM & HTTP** — heap/non-heap by pool, GC pause time and collection rate, threads (low, by
  design: the app runs on virtual threads), classes, CPU, request rate, p95 latency and a
  server-error ratio.
- **Modules** — `app.module.operation{module,operation,outcome}` covers every module boundary
  in one panel, plus assistant token usage and stream errors, chat lifecycle counters, and
  MCP tool invocations and latency.

Latency panels need histograms, so
`management.metrics.distribution.percentiles-histogram.*` is enabled for
`http.server.requests`, `app.module.operation` and `mcp.tool`; without it timers publish only
count and sum and every quantile panel is empty.

Instrumentation is explicit rather than `@Timed`: the annotation needs an AOP starter, and
`pom.xml` is frozen. Each module owns its own metric-name constants, because a module that
imported another's constants would be depending on it.

### What is emitted

- Traces: spans `spring.ai.chat.client` and `gen_ai.client.operation` per streamed turn, plus
  HTTP server spans; Loki lines link to them through the `trace_id` derived field.
- Metrics: `assistant.chats.created`, `assistant.chats.deleted`,
  `assistant.stream.errors{classification,error.type}`, `assistant.tools.invoked{tool,outcome}`,
  `assistant.tokens{type}`, `mcp.tool.invocations{tool,outcome}`, `app.module.operation{...}`,
  the JVM/HTTP set, and Spring AI's `gen_ai.*`.
- Logs: `ContentBlockLogger` logs content-block transitions and per-block chunk counts at
  INFO (deltas at DEBUG, delta content at TRACE) and finish reason + token usage at INFO.
  With temperature 0.5, Anthropic thinking blocks are disabled (thinking requires
  temperature 1); the logger handles thinking/signature/redacted blocks generically if ever
  enabled. Prompt/completion logging is on via
  `spring.ai.chat.observations.log-prompt/log-completion`.

## Tools (MCP)

The assistant cannot read a clock: asked the time it correctly refuses, saying it has no
access to real-time information. Tools are how that refusal becomes an answer.

Type `/` in the composer to open the tool palette; picking a tool runs it and records the
output as an assistant message, so a reload shows what you saw **and** the next turn replays
the result as model context.

The `mcp` module is a real MCP server, not an in-process shortcut. `chat` reaches it through
an outbound port (`McpToolGateway`) whose adapter POSTs to `${mcp.client.base-url}/mcp` —
this application's own endpoint by default. Moving the server into a service of its own is a
change to that one property.

### Why the protocol is hand-written

It speaks MCP revision **2026-07-28**, which deleted the machinery earlier revisions were
built on:

| Removed in 2026-07-28 | Consequence |
|---|---|
| `initialize` handshake and `notifications/initialized` | a client's first request can be `tools/list` |
| protocol sessions (`Mcp-Session-Id`) | nothing to store, nothing to expire |
| the standalone GET/SSE stream and `Last-Event-ID` | one POST per message, no long-lived connection |

Instead, every request carries its own protocol version, client identity and capabilities in
`_meta.io.modelcontextprotocol/*`, mirrored into the `MCP-Protocol-Version`, `Mcp-Method` and
`Mcp-Name` headers so proxies can route without parsing the body. The server rejects any
request where a header and the body disagree (`-32020`), because otherwise a load balancer
could route on one value while the server executes another.

Spring AI's MCP starter was evaluated and rejected: the MCP Java SDK still tracks
`2025-11-25`, where `spring.ai.mcp.server.protocol=STATELESS` only drops the session id and
keeps the handshake on the wire. Implementing the newer revision directly also costs no
dependencies — it is plain Spring MVC and Jackson.

### Trying it

```
curl -s localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
       "io.modelcontextprotocol/protocolVersion":"2026-07-28",
       "io.modelcontextprotocol/clientInfo":{"name":"curl","version":"1.0"},
       "io.modelcontextprotocol/clientCapabilities":{}}}}'
```

```json
{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
  {"name":"get_current_hour","title":"Current hour",
   "description":"Returns the current time of day on the server, with its time zone.",
   "inputSchema":{"additionalProperties":false,"type":"object"}}]}}
```

Calling it adds `-H 'Mcp-Name: get_current_hour'` and `"name"`/`"arguments"` to `params`:

```json
{"jsonrpc":"2.0","id":2,"result":{"resultType":"complete",
 "content":[{"type":"text","text":"The current time is 22:38 (Etc/UTC)."}],"isError":false}}
```

`GET` and `DELETE /mcp` answer **405**: they belonged to the session transport this revision
removed. An `Mcp-Session-Id` from an older client is ignored and never echoed.

### Adding a tool

Implement `McpTool` and annotate it `@Component`. The registry picks it up, sorts it into the
catalogue (the spec asks for a deterministic order so clients can cache the list), meters it
and audits it. Nothing else changes.

Errors follow the specification's split: a malformed request is a JSON-RPC error, while a
tool that *ran and failed* returns a normal result with `isError: true`, so a model can read
the reason and correct itself.

## Running

### Prerequisites

Copy `.env.example` to `.env` and set your key (git-ignored):

```
ANTHROPIC_API_KEY=sk-ant-...
```

### Dev (with observability stack via Testcontainers — needs Docker or podman)

```
./mvnw spring-boot:test-run
```

App: `http://localhost:8080`. Grafana port is dynamic — look for the LGTM container line in
the startup logs.

### Dev (plain, no containers)

```
./mvnw spring-boot:run
```

OTLP export will retry against localhost and log warnings; functionality is unaffected.

### Production-style (podman compose)

```
podman compose up --build
```

App: `http://localhost:8080`, Grafana: `http://localhost:3000`. Chat history lives in
`./data` (mounted into the container), so `podman compose restart app` keeps all chats, and
telemetry lives in the `lgtm-data` volume, so it survives a rebuild too.

Note for podman-compose: `up --build` does not always recreate a running container even when
the image changed. If a change does not seem to take effect, `podman compose down` first.

Note: `spring-boot-docker-compose` is deliberately **not** used — it shells out to a literal
`docker` binary, which breaks under podman.

### H2 console

`http://localhost:8080/h2-console` — JDBC URL
`jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE`, user `sa`, empty password. The console
runs inside the app's own JVM, so it needs no server mode.

`AUTO_SERVER=TRUE` is deliberately absent: H2 2.x rejects it in combination with
`DB_CLOSE_ON_EXIT=FALSE` (`Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"`,
thrown while opening the database), because mixed mode relies on the JVM shutdown hook that
`DB_CLOSE_ON_EXIT=FALSE` disables. `DB_CLOSE_ON_EXIT=FALSE` is the one worth keeping — it
leaves connection lifecycle to Hikari and Boot's graceful shutdown instead of racing H2's
own hook. Mixed mode would not work through compose anyway: it advertises an ephemeral port
inside the container that is never published.

`spring.jpa.hibernate.ddl-auto=update` is mandatory here: Boot does not treat a file-based
H2 database as "embedded", so the default would be `none`; `update` also creates Spring
Modulith's `EVENT_PUBLICATION` table.

## Tests

```
./mvnw test
```

| Test | What it covers |
|---|---|
| `ModularityTests` | `ApplicationModules.verify()` (which now enforces the named-interface boundaries, not just module names), an assertion pinning the module set to exactly `{assistant, audit, chat, mcp}`, and Modulith docs generation (`target/spring-modulith-docs`) |
| `ClaudePersonalAssistantApplicationTests` | context boots against the Testcontainers LGTM stack (needs Docker/podman) |
| `ChatPageControllerTest` / `ChatStreamControllerTest` | `@WebMvcTest` slices; the stream test drives MockMvc's `asyncStarted()` → `asyncDispatch()` two-step and asserts NDJSON lines |
| `ChatModuleIntegrationTests` / `ChatContextWindowTests` | `@ApplicationModuleTest` with a mocked `AssistantClient`: lifecycle events, persistence + rehydration, partial-answer persistence on failure, window semantics |
| `ChatOutboxAtomicityTests` | outbox atomicity: an event published in a transaction that rolls back leaves no `EVENT_PUBLICATION` row, because the registry's own JPA write joins the caller's transaction |
| `ChatClientAssistantTest` | real `ChatClient` over a mocked `ChatModel`: block filtering and prompt ordering (system → history → user) |
| `AnthropicErrorClassifierTest` / `ContentBlockLoggerTest` | classification matrix; block-transition logging |
| `AssistantModuleEventTests` | stream failure publishes a classified `AssistantErrorEvent` |
| `AuditModuleTests` | events increment the audit counters (with tags) |
| `AuditModuleListenerFailureTests` | outbox retry: a throwing listener leaves *its own* publication incomplete (the precondition for `republish-outstanding-events-on-restart`) while the real auditor still completes for the same event |
| `McpEndpointControllerTest` | the 2026-07-28 wire contract: `tools/list` as a first request with no handshake, `tools/call`, tool failure as `isError` rather than a JSON-RPC error, unknown tool → `-32602`, unknown method → 404 + `-32601`, header/body mismatch → `-32020`, base64-encoded `Mcp-Name`, missing and unsupported protocol version, `GET`/`DELETE` → 405, and a session id being ignored rather than echoed |
| `HttpMcpToolGatewayTest` | what the outbound adapter puts on the wire — required headers, the `_meta` block, and (via `MockRestServiceServer.verify()`) that discovery and invocation each cost exactly one request, with no `initialize` in front |
| `McpModuleEventTests` | a tool invoked **outside** any transaction still reaches its listener. Deliberately not driven through `Scenario`, which wraps its stimulus in a transaction and would hide the failure; it asserts on the auditor's counter, since the registry row is written either way but the counter only moves if the listener really ran |
| `DefaultToolRegistryTest` / `CurrentHourToolTest` | deterministic tool ordering, duplicate-name rejection at startup, failure → `isError`, and the clock-driven tool against a fixed `Clock` |
| `ToolControllerTest` | the `/tools` palette endpoint and tool execution, including a missing chat → 404 |
| `OpenTelemetryLogRecordAppenderTest` / `OpenTelemetryLoggingBridgeTest` | the hand-written log bridge: severity mapping, `addKeyValue` pairs and MDC promoted to attributes, exception attributes, trace context attached, and attach/detach against the root logger |

Run a single class/method:

```
./mvnw test -Dtest=ChatModuleIntegrationTests
./mvnw test -Dtest=ChatModuleIntegrationTests#happyPathStreamPersistsBothMessagesAndRehydratesOnReopen
```

Note: the `spring-ai-spring-boot-testcontainers` dependency is unused by this app (it only
provides vector-store/Ollama connection details) but is left in place per project policy of
not modifying `pom.xml`.
