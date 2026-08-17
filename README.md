# claude-personal-assistant

A server-rendered, multi-conversation chat backed by Claude (Anthropic) via Spring AI, with
token-by-token streaming over the servlet OutputStream, persistent per-chat history in
file-based H2, and full observability (traces, metrics, logs) in a Grafana LGTM stack.
Single user — no auth or user management, which is a deployment constraint rather than a
missing feature: read [Exposure and access](#exposure-and-access) before putting it on a network.

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

Everything (traces, metrics, logs) is pushed over OTLP to a Grafana LGTM stack. The image runs
Prometheus, Loki, Tempo, Pyroscope and Grafana behind an **OpenTelemetry Collector**
(`otelcol-contrib`) — not Grafana Alloy; nothing in this project speaks to Alloy.

Several things below are worth knowing, because each one silently produces an empty Grafana if
you get it wrong.

**Boot ships no Logback appender.** It auto-configures an `SdkLoggerProvider` and a batch
`LogRecordProcessor` unconditionally — gated only on `@ConditionalOnEnabledOpenTelemetry`
(`management.opentelemetry.enabled`, `matchIfMissing=true`) — and then nothing feeds them, so
log export appears configured and exports nothing. The documented remedy,
`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, has **no released
version** (61 published, every one `-alpha`). So `audit/logging/` carries a small appender
written against `io.opentelemetry.api.logs`, GA since 1.27.0 and documented as the API for
exactly this, plus a bridge that attaches it to the root logger once the context is up.

The *exporter* is a separate story, and the one that used to bite hardest: it is **not**
auto-configured unconditionally. `OtlpLoggingProperties` and `OtlpTracingProperties` both
declare a `@Nullable endpoint` with **no default**, their `ConnectionDetails` beans are
`@ConditionalOnProperty` on that endpoint, and the exporters are `@ConditionalOnBean` on those.
So without an endpoint Boot builds the provider, the bridge attaches, 100% of spans are
recorded — and every one of them lands in a `Noop` exporter, with no `ConnectException` and no
warning to read. `application.properties` therefore sets both defaults explicitly:

```
management.opentelemetry.logging.export.otlp.endpoint=${OTEL_LOGS_ENDPOINT:http://localhost:4318/v1/logs}
management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
```

Metrics never had this problem — Micrometer's `OtlpConfig` hardcodes
`http://localhost:4318/v1/metrics` — which is exactly why the symptom read as "traces and logs
are broken" rather than "no endpoint configured". Note that putting `OTEL_EXPORTER_OTLP_ENDPOINT`
in `.env` does **not** fix it: Boot's `OpenTelemetryEnvironmentVariables` reads `System::getenv`,
never the Spring `Environment` that `spring.config.import=optional:file:.env` feeds. Use a real
env var (compose does) or the `OTEL_TRACES_ENDPOINT`/`OTEL_LOGS_ENDPOINT` placeholders above.
Compose still wins over both defaults: the post-processor installs its mapping with `addFirst()`.

The appender maps `event.getKeyValuePairs()` onto attributes, which matters more than it sounds:
this application logs structured facts through `log.atInfo().addKeyValue(...)` everywhere, so
dropping them would leave Loki with message bodies and nothing to query on. A line arrives
carrying `chatId`, `title`, `blockType`, `finishReason` and the current `trace_id`. Records
logged before the Spring context is ready are not exported; console output is unaffected.

Four details in that appender are load-bearing:

- **MDC is written first, key-value pairs second.** Both go through `setAttribute`, and an equal
  `AttributeKey` replaces what was there — so the reverse order lets a colliding MDC key silently
  overwrite the structured fact the dashboards query on.
- **`traceId`/`spanId` are skipped when mapping the MDC.** `setContext()` already carries the
  trace natively, and that native pair is what produces the `trace_id`/`span_id` Grafana's derived
  field matches; mapping the MDC copies too would ship every id twice.
- **Records from `io.opentelemetry.*` are dropped.** `jul-to-slf4j` plus Boot's `SLF4JBridgeHandler`
  route the exporter's own failure records to the root logger this appender sits on, so exporting
  them feeds the failing pipeline back into itself and a collector outage gets louder from inside.
- **Stack traces are truncated at 16 384 characters** (`MAX_STACKTRACE_CHARACTERS`, with an explicit
  marker appended). Loki's effective `max_structured_metadata_size` is 64 KB and it rejects the
  *whole* entry with HTTP 400 rather than trimming the offending field, so an untruncated
  Anthropic-SDK-plus-Reactor cause chain would cost the error line entirely. A quarter of the cap,
  not a hair under it, because the limit counts bytes while the constant counts characters.

**Traces are sampled at 10% by default.** `management.tracing.sampling.probability=1.0` —
on a single-user app the default makes Tempo look broken.

**Context does not cross the async handoff unless you ask.**
`spring.task.execution.propagate-context=true` installs Boot's context-propagating
`TaskDecorator` (`TaskExecutorConfigurations$TaskExecutorContextPropagationConfiguration` is
conditional on that property with no `matchIfMissing`, so it is off by default). MVC async is
bound to `applicationTaskExecutor`, and `ChatStreamController` hands the entire assistant call to
a `StreamingResponseBody` — so without it the observation scope stays behind: one chat turn
arrives in Tempo as **three unrelated root traces**, every line logged on a `task-N` thread
(content-block transitions, token usage, tool audits) carries **no `trace_id`** so the Tempo→Loki
pivot finds nothing, and `streamTurn`/`assistant.stream` get zero exemplars. It needs no
dependency: `io.micrometer:context-propagation` is already compile-scope and
`micrometer-observation` registers `ObservationThreadLocalAccessor` through `META-INF/services`.

**Outbound MCP and Google calls need the registry passed by hand.** `RestClient.builder()` starts
at `ObservationRegistry.NOOP`, and with no `spring-boot-restclient` module on the classpath
nothing auto-configures it back — so `McpConnections.restClient(...)` and the three
`GoogleClientConfiguration` beans take an injected `ObservationRegistry` and call
`.observationRegistry(...)` explicitly. Without it those calls record no client observation and
send no `traceparent`, and every outbound MCP round trip appears in Tempo as a parentless root
trace with no link to the request that caused it.

**The test classpath shadows the main config.** `src/test/resources/application.properties`
does not merge with `src/main/resources/application.properties`; `target/test-classes` precedes
`target/classes` and Boot takes the first `classpath:/application.properties` it finds. So
`spring-boot:test-run` — which launches from the test classpath — used to run on the test file
alone: OTLP export off, sampling back to 10%, no histogram buckets. Everything that must hold in
*every* run mode therefore lives in `src/main/resources/observability.properties`, imported by
*both* `application.properties` files. That file now carries more than sampling and buckets:

| Setting | Why it cannot live in `application.properties` |
|---|---|
| `management.tracing.sampling.probability=1.0` | the 10% default makes Tempo look broken |
| `management.otlp.metrics.export.step=15s` | the 1m default leaves every panel blank for a minute after boot |
| `management.metrics.distribution.percentiles-histogram.*` | without them every quantile panel reads nonsense |
| `management.metrics.distribution.maximum-expected-value.{http.server.requests,app.module.operation}=5m` | Micrometer's `AbstractTimerBuilder` caps buckets at 30s, so a streamed turn pins p95 at ~30 000 ms forever — confidently wrong, not empty. Deliberately **not** applied to `mcp.tool`: a tool call is a short local round trip and 30s is the right ceiling there |
| `spring.task.execution.propagate-context=true` | see above — without it one turn is three root traces |
| `spring.ai.anthropic.max-retries` and the three `spring.ai.chat.observations.*` switches | run-mode agnostic; under `spring-boot:test-run` the `gen_ai` spans used to arrive with no prompt or completion content and a failed call logged nothing |

Everything the *main* file still owns is restored for the dev run by
`TestClaudePersonalAssistantApplication.DEV_RUN_OVERRIDES` as command-line arguments (which
outrank `application.properties`, and affect only that `main`, so the suite keeps the behaviour it
asserts on). It is no longer just the export switches: it re-enables all three exporters **plus**
`management.opentelemetry.enabled`, and restores the 5m async timeout, the file-based datasource
URL, `ddl-auto=update`, the H2 console, the three `spring.modulith.events.*` settings and the
whole `mcp.servers[0]` block. Anything added to the main `application.properties` that the dev run
also needs must go into `observability.properties` or that override list, or it will apply in
production and nowhere else.

**A container clock behind the host empties every metric panel.** Metric samples are stamped
with the JVM's clock; if the podman/Docker VM lags the host (common on macOS after sleep),
Prometheus sees them in its own future and drops them. The collector reports this only as
`otelcol_exporter_send_failed_metric_points_total` climbing in lockstep with
`otelcol_receiver_accepted_metric_points_total` — the app logs nothing and traces and logs keep
working, so it reads as "metrics are broken". Fix the clock, not the config:
`podman machine ssh <machine> "sudo date -s @$(date +%s)"`.

**otel-lgtm keeps everything under `/data`.** Without a volume, every restart wipes Grafana,
Loki, Prometheus and Tempo at once. `compose.yaml` mounts a named `lgtm-data` volume, and
gates the app on `condition: service_healthy` so it cannot start pushing before the collector
is listening.

### Where the data goes

- **Dev/test**: `./mvnw spring-boot:test-run` boots the app with a Testcontainers-managed
  `grafana/otel-lgtm` container wired via `@ServiceConnection` — no compose file needed.
- **Prod**: `compose.yaml` runs the LGTM stack next to the app; Boot 4.1 maps the native
  `OTEL_EXPORTER_OTLP_*` env vars onto its own properties, appending the per-signal path.
  `OTEL_EXPORTER_OTLP_PROTOCOL` is the one that behaves differently per signal:
  `mapTracesEnvironmentVariables` and `mapLogsEnvironmentVariables` map it onto
  `management.opentelemetry.{tracing,logging}.export.otlp.transport`, but
  `mapMetricsEnvironmentVariables` does not — it covers endpoint, temporality, histogram flavour,
  compression, timeout, headers, interval, exporter and SSL, and leaves protocol alone because the
  Micrometer OTLP registry is HTTP-only. Harmless at the configured `http/protobuf`. Switching it
  to `grpc` does **not** move anything to `:4317`: the endpoint comes from a separate mapping and
  stays `http://lgtm:4318/v1/traces`, so all `grpc` does is aim a gRPC exporter at the HTTP port
  and path — traces and logs stop exporting while metrics keep working.
  `service.instance.id` is a sharper version of the same asymmetry. Nothing produces one on its
  own, and the image's `prometheus.yaml` promotes that attribute to the `instance` label, so
  without it every series carries an empty `instance` and the image's own dashboards render blank.
  Setting the standard `OTEL_RESOURCE_ATTRIBUTES` env var is **not** enough: measured against a
  live stack it populated `resource.service.instance.id` on Tempo spans and `service_instance_id`
  on Loki records while Prometheus `target_info` for this job stayed bare. The line that works for
  all three is in `observability.properties`:

  ```properties
  management.opentelemetry.resource-attributes[service.instance.id]=${OTEL_SERVICE_INSTANCE_ID:cpa-1}
  ```

  The brackets are load-bearing, not stylistic. The value binds into a `Map<String, String>` whose
  key itself contains dots, and written as `…resource-attributes.service.instance.id` the relaxed
  binder reads those dots as further nesting and the attribute never reaches the resource — the
  symptom is exactly the empty `instance` the line exists to fix, which is why this was verified
  against a live `target_info` rather than assumed. `compose.yaml` feeds it `OTEL_SERVICE_INSTANCE_ID`.
- Grafana: `http://localhost:3000` (compose, now behind a login) or the mapped port Testcontainers
  logs.

### Dashboards and provisioning

Four dashboards are provisioned from `observability/grafana/dashboards/`, alongside the ones the
image ships itself — under compose by bind mount, and under `spring-boot:test-run` by
`withCopyFileToContainer` in `TestcontainersConfiguration`, so both ways of running show the same
Grafana. (The dashboards entry copies the whole *directory*, so adding a `*.json` needs no code
change; adding a single-file mount does.)

- **JVM & HTTP** (`cpa-jvm-runtime`) — heap/non-heap by pool, GC pause time and collection rate,
  threads (low, by design: the app runs on virtual threads), classes, CPU, request rate, p95
  latency and a server-error ratio.
- **Modules** (`cpa-modules`) — the `app.module.operation` boundary timers, assistant token usage
  and stream errors, chat lifecycle counters, and MCP tool invocations, latency and rejections.
- **LLM cost** (`cpa-llm-cost`) — 24h and 30d Anthropic spend, output tokens/min, and spend by
  model. See [Where the prices live](#llm-cost-where-the-prices-live) below; every money panel reads
  `$0.00` until the prices are filled in.
- **Logs & Traces** (`cpa-logs`) — the raw application log, an error-rate timeseries derived from
  `detected_level`, a tool-invocation log filtered on the `tool` field, and a Tempo table of recent
  traces. Its `$service` is a **textbox** variable (default `claude-personal-assistant`), not a
  `label_values()` query: a Loki label query returns nothing before the first line is ingested, and
  Loki *rejects* a selector whose matchers are all empty-compatible rather than returning an empty
  result — so a query variable would make the dashboard fail at exactly the moment you open it to
  check the stack came up.

Two counter-intuitive things the Modules dashboard had to work around, both cold-start failures:

- Its `$job` variable is seeded from `label_values(jvm_memory_used_bytes, job)`, not from an app
  metric. `app_module_operation_milliseconds_count` does not exist until a Timer has fired, so on a
  fresh stack `$job` resolved empty, every `expr` became `job=~""`, and all nine panels read "No
  data".
- The chat stat tiles use `sum(increase(...[$__range])) or vector(0)` rather than a bare counter.
  The counter is per-JVM and resets on every rebuild, so the tile dropped to 0 while H2 still held
  every chat; `increase()` is restart-correct, and the `or vector(0)` keeps the tile from going
  blank when nothing happened in range.

**The datasource is app-owned too.** `observability/grafana/provisioning/datasources/app-datasources.yaml`
adds a second Prometheus datasource, uid `prometheus-15s`, and both project dashboards point at it.
The image's own datasource declares `jsonData.timeInterval: 60s`, which Grafana turns into a
`$__rate_interval` of at least four minutes — while `observability.properties` exports every 15s, so
four out of five exported points were averaged away and a short burst flattened into the baseline.
The new datasource restates the real 15s step and copies the image's `exemplarTraceIdDestinations`
verbatim so the metric→trace exemplar pivot survives the repoint. It is deliberately **not**
`isDefault`: the image's `prometheus` uid stays the default, so the dashboards it ships keep working.

**Loki retention is bounded by `observability/loki/loki-config.yaml`.** The image ships no
`limits_config` and no `compactor`, which means `retention_period: 0s` (keep forever) with
`retention_enabled: false` — chunks are never deleted and `lgtm-data` grows until the disk does,
surfacing months later as a full disk with nothing pointing at Loki. That file is a **verbatim copy
of the image's own config** plus a `limits_config` (`retention_period: 744h`) and a `compactor`
block; re-sync it whenever the image tag is bumped, or the stale copy silently reverts upstream Loki
changes:

```
podman run --rm --entrypoint sh docker.io/grafana/otel-lgtm:latest -c "cat /otel-lgtm/loki-config.yaml"
```

`retention_enabled: true` is what makes `retention_period` mean anything, and
`delete_request_store: filesystem` is mandatory once it is on — Loki refuses to start without it.
`TestcontainersConfiguration` copies the same file, so a mistake there fails the test run instead of
first appearing on the deployed stack.

Latency panels need histograms, so
`management.metrics.distribution.percentiles-histogram.*` is enabled for
`http.server.requests`, `app.module.operation` and `mcp.tool`; without it timers publish only
count and sum and every quantile panel is empty.

Instrumentation is explicit rather than `@Timed`: the annotation needs an AOP starter, and
`pom.xml` is frozen. Each module owns its own metric-name constants, because a module that
imported another's constants would be depending on it.

<a id="llm-cost-where-the-prices-live"></a>
### LLM cost — where the prices live and how to update them

**`src/main/resources/pricing.properties`. That is the only file to edit.** Prices are USD per
**million** tokens, keyed by model and then by token type, and they ship as `0.0` — fill them in
from <https://www.anthropic.com/pricing>. A zero renders as `$0.00` and is obviously unset; a
plausible-but-stale number renders as a believable figure and is not, which is why nothing in this
repo guesses them.

```properties
app.llm.pricing.prices[claude-haiku-4-5-20251001].input=0.0
app.llm.pricing.prices[claude-haiku-4-5-20251001].output=0.0
app.llm.pricing.prices[claude-haiku-4-5-20251001].cache-write=0.0
app.llm.pricing.prices[claude-haiku-4-5-20251001].cache-read=0.0
```

Restart the app after editing. There is no hot reload: `@RefreshScope` and `/actuator/refresh` come
from Spring Cloud Context, which this project does not depend on and — `pom.xml` being frozen — will
not. The gauge does re-read the properties on every scrape, so a rebind *would* be picked up;
nothing performs one.

**Three things about this file are load-bearing.**

- **It is its own file, imported by both `application.properties` files** (`spring.config.import`).
  The test one shadows the main one rather than merging with it, so a price written in the main file
  would be invisible to `spring-boot:test-run` — the mode whose whole purpose is a populated
  Grafana. Same reason `observability.properties` exists; kept separate from it so "where do I
  update prices" is not a search through 150 lines of exporter commentary.
- **The key is the model Anthropic returns, not the alias the app requests.**
  `spring.ai.anthropic.chat.model` asks for `claude-haiku-4-5`; a live response resolves that to the
  dated snapshot `claude-haiku-4-5-20251001`, and the dated form is what lands on the
  `gen_ai_response_model` label the cost query joins on. Key this by the alias and the gauge joins to
  nothing and every panel reads `$0.00` forever, for a reason that looks exactly like an unset price.
  `AnthropicTokenAccountingVerificationTest` prints the resolved name; check it after a model change.
- **Bracket notation is required**, not stylistic: an unbracketed map key goes through the relaxed
  binder and would not round-trip a model id verbatim. Same reason as
  `management.opentelemetry.resource-attributes[service.instance.id]`.

A model that appears in traffic with no pricing entry is named once in a WARN by
`UnpricedModelWarner` — its tokens are still counted, they just cost nothing on the dashboard, which
reads exactly like a quiet week.

**How the money is computed: entirely in Prometheus.** Nothing in Java multiplies tokens by a price.
`LlmTokenPriceMeterBinder` publishes the configured prices as a gauge,
`llm.token.price.usd.per.mtok`, tagged `gen_ai.response.model` and `gen_ai.token.type` — byte for
byte the tags Spring AI already puts on `gen_ai.client.token.usage`. Both mangle to the same
Prometheus labels, so the join needs no `label_replace()`:

```promql
sum by (gen_ai_response_model, gen_ai_token_type) (
  increase(gen_ai_client_token_usage_total{gen_ai_token_type!="total"}[24h])
) / 1e6
* on (gen_ai_response_model, gen_ai_token_type) group_left()
  llm_token_price_usd_per_mtok
```

`gen_ai_token_type="total"` is its **own series** alongside `input` and `output` — Spring AI emits
all three from one response — so any sum that does not exclude it counts every token twice. Filter
with `!="total"` or select `="total"` deliberately, never neither.

That expression is recorded once a minute as `llm:spend_usd:24h` (and `llm:spend_usd:30d`) by
`observability/prometheus/llm-cost-rules.yaml`, and the Stat panels read the recorded series rather
than walking a day — or a month — of samples on every refresh. Adding a recording rule meant
replacing the image's `prometheus.yaml` wholesale, because Prometheus has no `--rules.file` flag and
the image declares no `rule_files`: `observability/prometheus/prometheus.yaml` is a **verbatim copy**
of the image's plus that one block, with the same re-sync caveat as `loki-config.yaml`.

**Cached tokens are billed separately and are not in that counter.** Anthropic reports
`cache_creation_input_tokens` and `cache_read_input_tokens` alongside `input_tokens`, and Spring AI
carries them on `Usage` but never feeds them to the metric — `ModelUsageMetricsGenerator` reads only
`getPromptTokens()`, `getCompletionTokens()` and `getTotalTokens()`. They reach Tempo as the span
attributes `gen_ai.usage.cache_creation.input_tokens` and `gen_ai.usage.cache_read.input_tokens`,
never Prometheus. This costs nothing today because **prompt caching is off**: Spring AI's
`AnthropicCacheOptions` defaults to `AnthropicCacheStrategy.NONE`, nothing here overrides it, and a
live call reports both cache figures as `0`. The `cache-write`/`cache-read` price rows exist so that
turning caching on is a price edit; if it is ever turned on, the cost panels will understate spend
until the cache tokens are metered too.

**Budget alert.** `observability/grafana/provisioning/alerting/llm-cost-alerts.yaml` provisions one
rule — `sum(llm:spend_usd:24h)` over budget for a continuous 15 minutes — plus a stub email contact
point to replace with your own. The budget is a **literal** `params: [10]` in that file, not an
environment variable, and that is deliberate: Grafana expands `$VARIABLE` in provisioning files for
datasources, dashboards and plugins but **not** for alerting. Measured on Grafana v13.1.1 in this
image, `params: [$LLM_SPEND_ALERT_USD]` provisioned healthily with the threshold stored as the
literal string — a budget that looks configured and is not. Note also that a malformed alerting file
is not a degraded Grafana but no Grafana: the provisioning module fails at startup and the container
never becomes ready.

### What is emitted

**Traces.** The observation *names* and the span names are not the same strings, which is worth
knowing before you search Tempo for something that does not exist. `spring.ai.chat.client` and
`gen_ai.client.operation` are Micrometer observation/metric names; the span names actually recorded
for one streamed turn are:

| Span | Where it comes from |
|---|---|
| `http <method> <route>` | the Spring MVC server span, e.g. `http POST /chats/{chatId}/messages/stream` |
| `stream` | this application's own `app.module.operation` boundary around `AssistantClient.stream(...)` |
| `spring_ai chat_client` | Spring AI's `ChatClient` observation (note the underscores — that is the literal span name, not a typo for the dotted metric name) |
| `chat claude-haiku-4-5` | Spring AI's model-call observation, named after the model |
| `tool _calling ` | Spring AI's tool-calling observation; the odd internal space and trailing space are real, so match on a prefix rather than the exact string |
| `POST` | the Anthropic SDK's own HTTP egress span, which carries no route |

Loki lines link to these through the `trace_id` derived field — **but only for lines emitted on a
thread that still holds the observation scope.** Before `spring.task.execution.propagate-context=true`
(above), every line logged on a `task-N` thread — which is all of the streaming ones — carried no
`trace_id` at all and the pivot found nothing. The correlation depends on that property, not on the
appender.

**Metrics.** `assistant.chats.created`, `assistant.chats.deleted`,
`assistant.stream.errors{classification,error.type}`, `assistant.tools.invoked{tool,outcome}`,
`assistant.tools.rejected{tool}`, `assistant.tokens{type}`, `mcp.tool.invocations{tool,outcome}`,
`mcp.tool` (timer), `app.module.operation{module,operation,outcome}`, the JVM/HTTP set, and Spring
AI's `gen_ai.*`.

`app.module.operation` is **not** emitted by all four modules, despite the name suggesting a
convention every module follows:

| Module | Boundary timer | Other meters |
|---|---|---|
| `assistant` | `app.module.operation{module=assistant,…}` (`AssistantMetrics.MODULE_OPERATION`) | `assistant.tokens{type}` |
| `chat` | `app.module.operation{module=chat,…}` (`ChatMetrics.MODULE_OPERATION`) | — |
| `mcp` | **none** — `McpMetrics` declares `mcp.tool.invocations` and the `mcp.tool` timer instead | — |
| `audit` | **none** — `AuditMetrics` declares no timer at all, only the five counters the listeners increment | the `assistant.*` counters above |

So the Modules dashboard's boundary panels show `assistant` and `chat` only; MCP has its own two
panels because its meters are differently named, and `audit`'s output is entirely counters. The
`module` tag exists so one panel can cover the modules that *do* time a boundary, and each module
still owns its own constants class — a module importing another's metric names would be depending
on it. If you add a boundary timer to `mcp` or `audit`, name it `app.module.operation`, tag it with
the module, and it joins the existing panels with no dashboard change.

**Logs.** `ContentBlockLogger` logs content-block transitions and per-block chunk counts at
INFO (deltas at DEBUG, delta content at TRACE) and finish reason + token usage at INFO.
With temperature 0.5, Anthropic thinking blocks are disabled (thinking requires
temperature 1); the logger handles thinking/signature/redacted blocks generically if ever
enabled. Prompt/completion logging is on via
`spring.ai.chat.observations.log-prompt/log-completion`. `McpConnections` also logs one line per
MCP server wired at startup (`server`, `category`, `protocol`, `url`) plus a count — which is what
makes a silently-dropped `mcp.servers[n]` entry visible without an error to grep for.

### Querying the logs in Loki

This is the part that reads wrong if you guess. **`service_name` is the only index label** —
`GET /loki/api/v1/labels` answers `["service_name"]` and nothing else. `detected_level`,
`trace_id`, `span_id` and every fact this application attaches with
`log.atInfo().addKeyValue(...)` — `chatId`, `tool`, `server`, `blockType`, `finishReason`,
`title` — all arrive as **structured metadata**. They show up inside the `stream` map of a query
result, which is what makes them look like labels, but a stream selector cannot match them:
`{detected_level="info"}` returns zero streams. Select on `service_name` first, then filter. So
this matches nothing at all:

```logql
{tool="gmail_create_draft"}          # zero streams: `tool` is not a label
```

and this is the shape that works — select on a label first, then filter:

```logql
{service_name="claude-personal-assistant"} | tool="gmail_create_draft"
{service_name="claude-personal-assistant"} | detected_level="error"
{service_name="claude-personal-assistant"} | chatId="42"
```

`chatId` and `conversationId` are deliberately **not** promoted to labels. A label's value set
becomes a Loki stream, and chat ids are unbounded — promoting them would create one stream per
conversation forever, which is the classic way to make Loki unusable. Filtering on structured
metadata costs a scan over the selected streams and is the right trade at this volume.

## Tools (MCP)

The assistant cannot read a clock: asked the time it correctly refuses, saying it has no
access to real-time information. Tools are how that refusal becomes an answer.

Three prefixes, all recognised only at the **start** of the composer: `!` opens the server picker,
`!/` the tool picker across every server, and `!<server>/` the tools of one server (`!localhost/`,
`!gmail/` — a short host label, not the full id). Tools are grouped by server and each one's
argument schema is rendered as a form.

Clicking a tool does not run it. It pre-fills the composer with `!<server>/<tool> ` and shows that
tool's call syntax — `!localhost/gmail_search_messages query=<string> maxResults=<integer>` — above
the form, so the picker teaches the typed form instead of replacing it. Writing the arguments after
the tool name as `name=value`, space separated and quoting any value that contains a space
(`query="is:unread from:me"`), then pressing Enter runs the tool; so does filling the fields and
pressing Run. Values are coerced by the type the schema declares, and an unresolved server, an
unknown tool, an unknown argument name or a missing required one is reported in the palette with
the line left in the composer to correct — never forwarded to the server or sent to the model. Only
a line with a `/` after the `!` is intercepted this way, so an ordinary message that happens to open
with `!` still sends. Nothing fires on a click for a tool that takes no arguments either: a single
stray click on `gmail_create_draft` or `calendar_delete_event` was too easy to hit.

Running a tool records the output as an assistant message, so a reload shows what you saw **and**
the next turn replays the result as model context. The line echoed into the transcript is the same
syntax the composer accepts, so a call made through the form can be edited and re-run by copying it
back. A bare `/` opens nothing on purpose: the client is
multi-server, a tool name is unique only *within* a server, and choosing a tool without naming its
server was always ambiguous. A `!` mid-sentence is just a `!`.

The `mcp` module is a real MCP server, not an in-process shortcut, and the client is a client of
**several** servers: `mcp.servers[n].*`, with `protocol=STATELESS` (revision `2026-07-28`, what our
own endpoint speaks) or `protocol=SESSION` (up to `2025-11-25`, what most third-party servers still
want). Leaving the list unset assumes one entry — our own `/mcp` over real HTTP loopback, id
`local` — so the protocol is genuinely exercised rather than short-circuited in process, and
extracting the server later is a property change. Listing any server explicitly *replaces* that
default, so include the local one if you still want it. An unreachable server is a row in the UI,
not an exception: `listServers()` never throws and `listTools()` skips what it cannot reach, so one
server being down never costs the model the others' tools.

One binder trap worth knowing before you edit that list: Spring Boot's indexed-list binder stops at
the **first missing index**, so commenting out `mcp.servers[0]` while `[1]`/`[2]` stay active binds
*nothing at all*, with no error — the list silently falls back to the single local default.
Uncomment or renumber the whole block together, never a suffix of it. `McpConnections` logs one
line per server it actually wired at startup, which is how that failure becomes visible.

### The tools that ship

| Tool | Needs |
|---|---|
| `get_current_hour` | nothing — always registered |
| `gmail_search_messages`, `gmail_get_message`, `gmail_create_draft` | `google.workspace.enabled=true` |
| `calendar_list_events`, `calendar_create_event`, `calendar_update_event`, `calendar_delete_event` | `google.workspace.enabled=true` |

The Google group calls the **ordinary** Gmail and Calendar REST APIs (not Google's remote MCP
servers, which are gated behind an approval-only Workspace preview programme — see `CLAUDE.md` for
why that path is closed), and it is off by default, so the app boots and the whole suite runs with
no Google credentials at all. Turning it on needs four values in `.env`:

```
GOOGLE_WORKSPACE_ENABLED=true
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GOOGLE_REFRESH_TOKEN=...
```

**Under compose these must be passed through explicitly**, and `compose.yaml` now does. The image
deliberately never copies `.env`, so the in-jar `optional:file:.env` resolves against `WORKDIR /app`,
finds nothing, and is skipped without a word — the symptom was a container holding zero `GOOGLE_*`
variables and `/tools` serving exactly one tool while `mcp/domain/tool/google` holds seven tools.

`.env` must hold the **refresh** token, never an access token: the grant is
`grant_type=refresh_token` and an access token dies in about an hour with no way to renew it. A
consent screen left in "Testing" expires that refresh token after 7 days.

Two boundaries in that group are structural rather than prompt instructions. **There is no send
path**: `GmailClient` has `createDraft` and no `messages.send`, so text injected into a mail the
model just read cannot talk it into emailing anyone. And `calendar_delete_event` — the only
destructive tool here — takes a required event id as its *sole* argument, so there is no query or
date range to get wrong and the model must have listed the event first.

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

Optionally also `GOOGLE_*` (see [The tools that ship](#the-tools-that-ship)) and
`GRAFANA_ADMIN_PASSWORD`, which compose feeds to `GF_SECURITY_ADMIN_PASSWORD` and which falls back
to `admin` when unset.

The three run modes are **not** interchangeable in what they persist and export. This is the
summary; the reasons are in [Observability](#observability):

| | `spring-boot:test-run` | `spring-boot:run` | `podman compose up` |
|---|---|---|---|
| Config file in effect | `src/test/resources/application.properties` + `DEV_RUN_OVERRIDES` | `src/main/resources/application.properties` | same as `spring-boot:run`, plus compose env vars |
| Telemetry backend | Testcontainers LGTM via `@ServiceConnection` | localhost:4318 (nothing there unless you run one) | the `lgtm` service |
| Traces & logs exported | yes | yes — **new**, see below | yes |
| Chat history | `./data/chat` (**new** — used to be a fresh in-memory DB per boot) | `./data/chat` | `/app/data/chat`, bind-mounted from `./data` |
| H2 console | yes (**new**) | yes | reachable, but always answers "not allowed" — see below |
| MCP tools | yes (**new** — `mcp.servers[0]` is passed explicitly) | yes | yes |
| Anthropic key | from `.env`, else `test-api-key` (**new** — used to be the literal dummy) | from `.env` | from compose's `.env` |

### Dev (with observability stack via Testcontainers — needs Docker or podman)

```
./mvnw spring-boot:test-run
```

App: `http://localhost:8080`. Grafana port is dynamic — look for the LGTM container line in
the startup logs.

This mode reads the **test** classpath, so it used to inherit every one of the test file's
deliberate restrictions: a dummy API key (so every streamed turn 401ed, which reads as a telemetry
failure rather than an auth one), a throwaway in-memory database, no H2 console and no MCP servers.
`TestClaudePersonalAssistantApplication.DEV_RUN_OVERRIDES` now restores all of it. If you add
something to the main `application.properties` that this mode also needs, add it there or to
`observability.properties` — it will not arrive by itself.

### Dev (plain, no containers)

```
./mvnw spring-boot:run
```

Metrics retry against `localhost:4318` and log warnings; that part is harmless and always was.
Traces and logs used to be *quieter than that and worse*: with no endpoint property they created no
exporter at all, so they warned about nothing and went nowhere. `application.properties` now
defaults both to `http://localhost:4318`, so this mode warns about all three signals equally and
starts working the moment anything is listening on that port. Functionality is unaffected either
way.

### Production-style (podman compose)

```
podman compose up --build
```

App: `http://localhost:8080`, Grafana: `http://localhost:3000` (now asking for credentials — see
[Exposure and access](#exposure-and-access)). Chat history lives in `./data` (mounted into the
container), so `podman compose restart app` keeps all chats, and telemetry lives in the `lgtm-data`
volume, so it survives a rebuild too.

Only Grafana and the app are published to `0.0.0.0`. OTLP (`4317`/`4318`), Tempo (`3200`) and
Prometheus (`9090`) are bound to `127.0.0.1` — Tempo and Prometheus because they are a debugging
surface rather than a service (the image's `run-all.sh` prints them as reachable even when they are
not published, and the container-clock check below needs `9090` to be queryable at all), and OTLP
because the app reaches the collector at `http://lgtm:4318` over the compose network and never
touches the host port, so publishing it on `0.0.0.0` bought nothing and cost an unauthenticated
write surface straight into the persisted `lgtm-data` volume.

The container and a host-side `./mvnw spring-boot:run` **cannot run at the same time**. The
relative `jdbc:h2:file:./data/chat` resolves to the very same host file from `/app` and from the
repo root, and with `AUTO_SERVER` correctly forbidden the second opener dies with "Database may be
already in use" — which reads like an unrelated startup crash. `compose.yaml` sets an absolute
`SPRING_DATASOURCE_URL` so at least the collision is explicit.

`restart: unless-stopped` is set on both services, but **rootless podman has no root daemon to
honour it across a reboot**. Once, as your user:

```
sudo loginctl enable-linger $USER && systemctl --user enable --now podman-restart.service
```

Note for podman-compose: `up --build` does not always recreate a running container even when
the image changed. If a change does not seem to take effect, `podman compose down` first.

Note: `spring-boot-docker-compose` is deliberately **not** used — it shells out to a literal
`docker` binary, which breaks under podman.

The build context is trimmed by `.dockerignore` (`target/`, `.git/`, `data/`, `.env`, editor and
tooling state). `data/` and `.env` are there for correctness, not size: the build stage would
otherwise bake real conversation history and real secrets into the image, and any container started
from it would pick that `.env` up, because the app resolves `optional:file:.env` against
`WORKDIR /app`. The `Dockerfile` also declares **no** `VOLUME /app/data` — it made every plain
`podman run` create an anonymous volume that looked persistent right up until something pruned
volumes. Compose owns the mount, so the storage decision stays where it is visible.

### H2 console

`http://localhost:8080/h2-console` — JDBC URL
`jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE`, user `sa`, empty password. The console
runs inside the app's own JVM, so it needs no server mode.

**It does not work under compose, for anybody.** The request answers `200` with a 556-byte
`notAllowed.jsp` — "Sorry, remote connections are not allowed" — no matter where it came from,
including `curl` on the host. That is not a misconfiguration to fix and no property switches it off:
H2's servlet guard is a plain `InetAddress.isLoopbackAddress()` check on the *remote address*, and
rootless podman re-originates every inbound connection through its userspace port forwarder, so the
app sees a peer of `10.89.x.x` (the container's own network) and never `127.0.0.1`. Verified against
the running stack:

```
$ curl -sL -o /dev/null -w '%{http_code} %{size_download}\n' http://localhost:8080/h2-console/
200 556
```

Use the console from a host-side `./mvnw spring-boot:run` or `./mvnw spring-boot:test-run` — both
open the same `./data/chat` file — and remember that only one process may hold it at a time. The
same re-origination is why the app can never see a real client IP under compose (see below).

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

## Exposure and access

### There is no authentication, and that is the design

Say it plainly, because everything else in this chapter follows from it: **this application has no
login, no session, no authorization check of any kind.** It was built for one person on one machine,
and the whole feature set is unauthenticated:

- every conversation is readable — `GET /` lists them, `GET /chats/{chatId}` opens any of them;
- every conversation is deletable: `POST /chats/{chatId}/delete`, with no confirmation server-side;
- every MCP tool is discoverable (`GET /mcp-servers`, `GET /tools`) and invocable
  (`POST /chats/{chatId}/servers/{serverId}/tools/{toolName}`, or straight at `POST /mcp`) — which,
  with `google.workspace.enabled=true`, means reading the owner's Gmail, drafting mail, and
  creating, editing and **deleting** calendar events, using credentials the caller never presented;
- prompts and completions are logged in full (`spring.ai.chat.observations.log-prompt=true`), so
  anyone who reaches the telemetry backend reads the conversations even without reaching the app.

Nothing here is a bug to be filed. It is a deployment constraint: **the only thing standing between
this app and everyone on your network is the host firewall.**

### The app binds every interface

No `server.address` is set, so Tomcat binds `0.0.0.0`, and compose publishes `8080:8080` on all
interfaces. The frontend uses only relative URLs, so it works from any hostname without
configuration — there is nothing to change to "enable" LAN access, and equally nothing that
disables it.

Under rootless podman the published ports are ordinary listening sockets owned by a userspace
`rootlessport` process:

```
$ ss -ltnp | grep 8080
LISTEN 0 4096 *:8080 *:* users:(("rootlessport",pid=…,fd=11))
```

This matters more than it looks. The well-known "Docker bypasses UFW" problem is a *DNAT* escape —
rootful Docker inserts rules into the `DOCKER` chain in `nat`, which is traversed before `INPUT`,
so `ufw` never sees the packet. **Rootless podman does not do that.** Traffic terminates in a normal
userspace socket and traverses `INPUT` like any other service, so `ufw` genuinely applies here.

### Scoping it with ufw

The host firewall is the gate. On this machine `ufw` is `ENABLED=yes` with
`DEFAULT_INPUT_POLICY="DROP"` and an empty `### RULES ###` block — that is, nothing inbound is
allowed at all, which is why the app is not currently reachable from the LAN. Confirm with:

```
sudo ufw status verbose
```

To let one subnet in, scope the rule to that subnet and that port:

```
sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp
```

Never `sudo ufw allow 8080`. That form matches **any** source, which on a laptop that also joins
café and hotel networks means the next untrusted network you associate with can read and delete
every conversation. Whatever the interface, the rule should name the source.

**A local `curl` proves nothing.** This is the trap worth spelling out:

```
curl http://192.168.1.50:8080/     # 200 — and it tells you exactly nothing
```

Traffic a host generates towards one of its *own* addresses is routed over the loopback interface,
and `ufw` accepts loopback unconditionally. It never traverses the rules you are trying to test. The
only valid check is a request from **another host** on the network; anything else is testing the
loopback path and reporting it as a firewall verdict.

### Grafana is no longer anonymous

The `otel-lgtm` image's `run-grafana.sh` defaults to `GF_AUTH_ANONYMOUS_ENABLED=true` with
`GF_AUTH_ANONYMOUS_ORG_ROLE=Admin`, and this file used to override neither — verified live against
the running stack, `GET /api/org/users` and `GET /api/datasources` both answered `200` with no
credentials. Since Grafana proxies Loki and `log-prompt=true` puts whole prompt and completion
bodies there, an anonymous org-Admin on `:3000` is a full read of every conversation, from a port
nobody thinks of as sensitive.

`compose.yaml` now sets `GF_AUTH_ANONYMOUS_ENABLED: "false"` and
`GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-admin}`. Log in as `admin`. Set
`GRAFANA_ADMIN_PASSWORD` in `.env` for anything reachable beyond your own machine; the `admin`
fallback is fine for a laptop-only stack and nothing else. **A running stack keeps the old anonymous
Grafana until it is recreated** — this change lands on the next `podman compose up`.

### Why there is no in-app IP allowlist

The obvious-looking alternative — check the client IP in a filter — cannot work here. Under compose
every request arrives from the container network's own address because of the same rootless
re-origination that breaks the H2 console, and no `server.forward-headers-strategy` is set, so there
is no `X-Forwarded-For` to recover the real peer from either. An allowlist would have nothing to
match on: it would see one address for the whole world and either block everyone or allow everyone.
Scoping belongs to `ufw`, where the real source address still exists.

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
| `OpenTelemetryLogRecordAppenderTest` / `OpenTelemetryLoggingBridgeTest` | the hand-written log bridge: severity mapping, `addKeyValue` pairs and MDC promoted to attributes, exception attributes, trace context attached, and attach/detach against the root logger. Four cases run through a **real** `SdkLoggerProvider` with an in-process `LogRecordProcessor`, because a mocked `LogRecordBuilder` cannot show a later attribute replacing an earlier one — which is exactly the MDC-versus-`addKeyValue` collision being pinned. They also cover the `traceId`/`spanId` de-duplication, the `io.opentelemetry.*` self-export drop, and stack-trace truncation |
| `McpConnectionsTest` / `GoogleClientConfigurationTest` | that each outbound `RestClient` is built with the injected `ObservationRegistry`. Driven against a just-closed loopback port (bound to `0`, then closed, so nothing can answer) and asserting on a recorded `http.client.requests` observation, not on a response — an unwired client fails with "There are no observations registered" |
| `ProductionDatasourceUrlTest` | the blind spot created by tests declaring no `spring.datasource.url`: it opens the *real* production URL, relocated to a temp directory, so a broken one cannot pass the suite |

Run a single class/method:

```
./mvnw test -Dtest=ChatModuleIntegrationTests
./mvnw test -Dtest=ChatModuleIntegrationTests#happyPathStreamPersistsBothMessagesAndRehydratesOnReopen
```

Note: the `spring-ai-spring-boot-testcontainers` dependency is unused by this app (it only
provides vector-store/Ollama connection details) but is left in place per project policy of
not modifying `pom.xml`.
