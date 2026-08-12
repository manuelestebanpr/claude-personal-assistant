# claude-personal-assistant

A server-rendered, multi-conversation chat backed by Claude (Anthropic) via Spring AI, with
token-by-token streaming over the servlet OutputStream, persistent per-chat history in
file-based H2, and full observability (traces, metrics, logs) in a Grafana LGTM stack.
Single user — no auth or user management.

- Spring Boot 4.1 (Java 25), WebMVC + Thymeleaf (no CDN assets — plain CSS/JS)
- Spring AI 2.0 Anthropic (`claude-haiku-4-5`, max-tokens 4000, temperature 0.5)
- Spring Data JPA + H2 (file-based, survives restarts)
- Spring Modulith 2.1 (module structure, event publication registry)
- OpenTelemetry export to Grafana LGTM (dev via Testcontainers, prod via compose)

## Module architecture (Spring Modulith)

Direct subpackages of `com.my.custom.claudepersonalassistant` are the modules. API types
(interfaces, records, events, enums) live at each module root; internals live in nested
subpackages (`web`, `service`, `persistence`, `config`, `client`, `error`, `logging`).

```
chat       conversation lifecycle, persistence, web UI + streaming endpoint
           ChatFacade (API), StreamEvent, ChatCreatedEvent, ChatDeletedEvent, ChatProperties
assistant  Spring AI / Anthropic integration
           AssistantClient (API), AssistantRequest/HistoryMessage, ErrorClassification,
           AssistantException, AssistantErrorEvent, AssistantConstants (system prompt)
audit      observability listeners
           ChatLifecycleAuditor, AssistantErrorAuditor, AuditMetrics
```

Allowed dependencies: `chat → assistant`; `audit → chat + assistant` (event/enum types only);
`assistant → nothing`. Spring AI types never leak out of `assistant`. `ModularityTests`
enforces this with `ApplicationModules.verify()`.

Layering inside `chat`: controller → `ChatFacade` (interface) → `ConversationService` /
`MessageService` (interfaces) → Spring Data repositories. Constructor injection everywhere,
records for DTOs/events, mapping paths and view/model names as constants on the controllers,
tunables in `ChatProperties`.

### Event flow

| Event | Published by | Listener (audit) | Output |
|---|---|---|---|
| `ChatCreatedEvent` | `DefaultConversationService.create()` (inside `@Transactional`) | `ChatLifecycleAuditor` | INFO log + counter `assistant.chats.created` |
| `ChatDeletedEvent` | `DefaultConversationService.delete()` | `ChatLifecycleAuditor` | INFO log + counter `assistant.chats.deleted` |
| `AssistantErrorEvent` | `AssistantErrorPublisher` (wrapped in a `TransactionTemplate`, because reactive error callbacks run without a transaction and `@ApplicationModuleListener` is AFTER_COMMIT) | `AssistantErrorAuditor` | ERROR log + counter `assistant.stream.errors{classification,error.type}` |

## How streaming works

1. The browser POSTs `{"content": "..."}` to `/chats/{chatId}/messages/stream`.
2. `ChatStreamController` returns a `StreamingResponseBody` that blocks over
   `Flux.toStream()`, writing one NDJSON line per event and flushing after each. Virtual
   threads (`spring.threads.virtual.enabled=true`) make the blocking iteration cheap;
   `spring.mvc.async.request-timeout=5m` gives generation headroom.
3. `DefaultChatFacade.streamAnswer` validates the chat (404 before the body starts), reads
   the history window (before saving), saves the user message, derives the title from the
   first message, then bridges `AssistantClient.stream(...)` into `StreamEvent`s. A
   `doFinally` hook persists the streamed answer — full on completion, partial on error or
   client disconnect — so a reload always matches what the user saw.
4. `ChatClientAssistant` replays history as user/assistant messages plus the shared system
   prompt (the only cross-chat context) and streams `ChatResponse` chunks, filtering to text
   deltas.
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

- **Dev/test**: `./mvnw spring-boot:test-run` boots the app with a Testcontainers-managed
  `grafana/otel-lgtm` container wired via `@ServiceConnection` — no compose file needed.
- **Prod**: `compose.yaml` runs the LGTM stack next to the app; Boot 4.1 picks up the native
  `OTEL_EXPORTER_OTLP_*` env vars.
- Grafana: `http://localhost:3000` (compose) or the mapped port Testcontainers logs at
  startup.
- Traces: spans `spring.ai.chat.client` and `gen_ai.client.operation` per streamed turn.
- Metrics: `assistant.chats.created`, `assistant.chats.deleted`,
  `assistant.stream.errors{classification,error.type}`, plus Spring AI's `gen_ai.*` metrics.
- Logs: `ContentBlockLogger` logs content-block transitions and per-block chunk counts at
  INFO (deltas at DEBUG, delta content at TRACE) and finish reason + token usage at INFO,
  with key-values (`conversationId`, `blockType`, ...) correlated to traces via OTel. Note:
  with temperature 0.5, Anthropic thinking blocks are disabled (thinking requires
  temperature 1); the logger handles thinking/signature/redacted blocks generically if ever
  enabled. Prompt/completion logging is enabled via
  `spring.ai.chat.observations.log-prompt/log-completion`.

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
`./data` (mounted into the container), so `podman compose restart app` keeps all chats.

Note: `spring-boot-docker-compose` is deliberately **not** used — it shells out to a literal
`docker` binary, which breaks under podman.

### H2 console

`http://localhost:8080/h2-console` — JDBC URL
`jdbc:h2:file:./data/chat;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE`, user `sa`, empty
password. (`AUTO_SERVER=TRUE` also lets a second process attach while the app runs; it
writes a lock file in `./data`.)

`spring.jpa.hibernate.ddl-auto=update` is mandatory here: Boot does not treat a file-based
H2 database as "embedded", so the default would be `none`; `update` also creates Spring
Modulith's `EVENT_PUBLICATION` table.

## Tests

```
./mvnw test
```

| Test | What it covers |
|---|---|
| `ModularityTests` | `ApplicationModules.verify()` + Modulith docs generation (`target/spring-modulith-docs`) |
| `ClaudePersonalAssistantApplicationTests` | context boots against the Testcontainers LGTM stack (needs Docker/podman) |
| `ChatPageControllerTest` / `ChatStreamControllerTest` | `@WebMvcTest` slices; the stream test drives MockMvc's `asyncStarted()` → `asyncDispatch()` two-step and asserts NDJSON lines |
| `ChatModuleIntegrationTests` / `ChatContextWindowTests` | `@ApplicationModuleTest` with a mocked `AssistantClient`: lifecycle events, persistence + rehydration, partial-answer persistence on failure, window semantics |
| `ChatClientAssistantTest` | real `ChatClient` over a mocked `ChatModel`: block filtering and prompt ordering (system → history → user) |
| `AnthropicErrorClassifierTest` / `ContentBlockLoggerTest` | classification matrix; block-transition logging |
| `AssistantModuleEventTests` | stream failure publishes a classified `AssistantErrorEvent` |
| `AuditModuleTests` | events increment the audit counters (with tags) |

Run a single class/method:

```
./mvnw test -Dtest=ChatModuleIntegrationTests
./mvnw test -Dtest=ChatModuleIntegrationTests#happyPathStreamPersistsBothMessagesAndRehydratesOnReopen
```

Note: the `spring-ai-spring-boot-testcontainers` dependency is unused by this app (it only
provides vector-store/Ollama connection details) but is left in place per project policy of
not modifying `pom.xml`.
