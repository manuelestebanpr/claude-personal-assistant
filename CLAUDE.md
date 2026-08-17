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
- Run plain (no containers, OTLP connection warnings on all three signals are harmless): `./mvnw spring-boot:run`
- Production-style: `podman compose up --build` (Grafana on `:3000` — now behind a login, `admin` / `${GRAFANA_ADMIN_PASSWORD:-admin}`; app on `:8080`; H2 file in `./data`; telemetry in the `lgtm-data` volume; OTLP, Tempo and Prometheus published on `127.0.0.1` only). podman-compose does not always recreate a running container when the image changes — run `podman compose down` first if a change seems not to take effect.
- A container and a host-side `spring-boot:run` cannot both run: both resolve `./data/chat` to the same host file and the second one dies with "Database may be already in use".
- The default JDK on this machine is 21; the build needs 25. Prefix commands with `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-tem` if `./mvnw` reports `release version 25 not supported` (check `ls ~/.sdkman/candidates/java` for the exact installed version — this path has changed before).

`ANTHROPIC_API_KEY` comes from a git-ignored `.env` (see `.env.example`), imported through `spring.config.import=optional:file:.env[.properties]`. The test file imports `.env` too and falls back to `test-api-key` (`${ANTHROPIC_API_KEY:test-api-key}`), so CI still runs keyless while `spring-boot:test-run` — which reads the *test* classpath — stops 401ing on every turn. That 401 impersonated a telemetry failure, which is why it survived: it classifies as TERMINAL, `ChatClientAssistant` never reaches `onComplete()`, so `assistant.tokens` never increments and the panels read empty rather than broken.

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

Adding a tool = one `@Component` implementing `McpTool`. Nothing else changes — but
`GoogleWorkspaceToolRegistrationTests.publishesEveryGoogleToolInADeterministicOrder` asserts
`containsExactly` over the **whole** registry, so a new tool lands in that list too. That is the one
place the complete published surface is written down.

`ToolSchema` and `ToolArguments` live in `mcp/domain/tool/`, not beside one tool group. They were
package-private inside `domain.tool.google` until a second group needed them; a new group in a
sibling package could not see them, which is the only reason they moved up.

### Groceries own a table inside `mcp`, and that was a deliberate trade

`mcp/persistence/GroceryEntity` is the first table this module has ever owned, reached through
`mcp/domain/grocery/GroceryStore` and five tools (`groceries_add`, `_add_many`, `_list`, `_delete`,
`_import_receipt`). JPA is a library rather than an application module, so `allowedDependencies = {}`
still holds and `ModularityTests` proves it.

What it costs: `mcp` is no longer a pure protocol module. A groceries **page** in `chat` could not
read this directly — it would need a read port through `mcp::api`, the same inversion described
below. That was accepted knowingly; do not "fix" it by reaching across.

`DefaultGroceryStore` is `@Transactional` and that is load-bearing, not decoration: tools are
invoked straight from the HTTP endpoint with **no transaction in scope**, so a bulk insert would
otherwise be able to land half-applied. Same hazard `ToolEventPublisher` guards from the other side.

### `ImageAnalysis` is an inverse port, and the only way a tool can reach the model

`groceries_import_receipt` has to read a photograph, which means reaching both the module that
stores images (`chat`) and the module that talks to the model (`assistant`) — and `mcp` may depend
on neither. So `mcp::api` **declares** `ImageAnalysis` and something above implements it:
`chat/service/ChatImageAnalysis`, because `chat` is the only module allowed to see `mcp::api` and
`assistant::api` at once. It is the same inversion `assistant::api.ToolExecutor` already uses in the
other direction, and no cycle appears.

The tool injects **`ObjectProvider<ImageAnalysis>`**, never the port itself. Booting `mcp` alone is
supported and nothing implements the port there; a hard dependency would stop the whole context and
take every other tool down with it. Absent provider → a `ToolExecutionException` that says so.

### Gmail and Calendar are local tools, because Google's own MCP servers are out of reach

`gmailmcp.googleapis.com/mcp/v1` and `calendarmcp.googleapis.com/mcp/v1` are Google's own remote
MCP servers — the same ones claude.ai's "Google Workspace connectors" reach. They were wired up as
two more `mcp.servers[]` entries and then switched off. **Do not wire them back in without first
checking the preview gate below.**

They are gated behind the **Google Workspace Developer Preview Program**, which is approval-based
and requires a Google Workspace account; this project runs on a consumer account, so the gate
cannot be passed. Enabling them needs *two* Cloud services per product — the ordinary API **and** a
separate MCP service (`gmailmcp.googleapis.com`, `calendarmcp.googleapis.com`). That name is a
service to enable, **never an OAuth scope**; looking for a "gmailmcp" scope is the wrong search.

The failure is deceptive and worth recognising. Google's frontend (ESF) **returns the complete
`tools/list` body no matter what** and encodes the verdict in the HTTP status alone:

| Request | Status | Body |
|---|---|---|
| no `Authorization` | `200` | full tool catalogue |
| invalid token | `401` + `www-authenticate: error="invalid_token"` | full tool catalogue |
| valid but unauthorised token | `403` | full tool catalogue |

So `McpServerConnection.describe()` reports the server unreachable with a `detail` string that
contains a 403 followed by 50 KB of perfectly good tool definitions. That is not a parsing bug.
Probing these servers with **no** `Authorization` header is the free, credential-free way to check
endpoint and protocol correctness — confirmed: `initialize` answers `200` with
`serverInfo {"name":"StatelessServer","version":"ESF"}` and issues no `Mcp-Session-Id`, and sending
`2026-07-28` instead of `2025-11-25` is rejected with `-32600`, so `protocol=SESSION` is right.

What actually runs instead is the local `McpTool`s in `mcp/domain/tool/google`, calling the
**ordinary** Gmail and Calendar REST APIs through `GmailClient`/`CalendarClient`. Those need no
preview access and work on a consumer account. Served by our own `/mcp`, so the model reaches them
over the same loopback MCP round trip as every other local tool.

`mcp.servers[].google-auth=true` (`McpProperties.Server.googleAuth`) is kept for the day preview
access arrives. An access token expires in about an hour and this application runs continuously, so
the static `mcp.servers[].authorization` header every other server uses would go stale;
`McpConnections`'s `restClient()` installs a request interceptor calling `GoogleAccessTokens
.current()` per outgoing request instead. `GoogleAccessTokens` is `public` for exactly this —
`mcp.client` calls `current()` per request and `McpModuleConfiguration` looks the bean up via
`ObjectProvider` (it only exists when `google.workspace.enabled=true`) — while still being
constructed only in `GoogleClientConfiguration`. A `google-auth=true` server with no
`GoogleAccessTokens` bean fails startup with a named `IllegalStateException` rather than sending an
unauthenticated request.

Scopes for the local tools: `gmail.readonly` + `gmail.compose`, plus
`https://www.googleapis.com/auth/calendar.events` — `calendar_create_event`,
`calendar_update_event` and `calendar_delete_event` write, so a read-only Calendar scope lists
events fine and fails every mutation with a 403. `.env` holds the **refresh** token, never an
access token; the grant is `grant_type=refresh_token`. A consent screen left in "Testing" expires
that refresh token after 7 days.

Google's docs document a redirect URI only for claude.ai's and Antigravity's own connectors
(`https://claude.ai/api/mcp/auth_callback` and Antigravity's equivalent); a third-party application
needs its own registered OAuth client and redirect URI, so this one is registered separately and
cannot reuse claude.ai's.

`calendar_delete_event` is the only destructive tool in the group. Its schema is the guard: a
required event id is the *sole* argument, so there is no query or date range to get wrong and the
model must have listed the event first.

### The client is multi-server; our own server is just the first entry

`McpToolGateway` is a client of **several** MCP servers, configured as `mcp.servers[n].*`. Leaving
the list unset assumes one entry — our own `/mcp` over **real HTTP loopback**, id `local` — so the
protocol is genuinely exercised rather than short-circuited in process, and extracting the server
later is a property change. Listing any server explicitly *replaces* that default, so include the
local one if you still want it.

- **Two revisions, chosen per server by `protocol`.** `STATELESS` (`2026-07-28`, what our server
  speaks: no `initialize`, no session) and `SESSION` (up to `2025-11-25`: `initialize` →
  `notifications/initialized` → `Mcp-Session-Id` on every later request, which is what most
  third-party servers still want). The seam is `McpWireClient`; everything above it — listing,
  calling, unpacking — is revision-independent.
- **A tool name is unique per server, not globally.** `ToolDescriptor` and `ToolInvocation` both
  carry `serverId` for that reason. The aggregated `listTools()` the model is offered drops a
  duplicate name from a later server and logs it, because the model addresses tools by bare name
  and could not express which it meant.
- **The model names a tool but never a server**, so `McpToolExecutor` resolves one from the
  catalogue and memoises it, dropping the route when a call fails so a restarted server recovers.
- **An unreachable server is a row, not an exception.** `listServers()` never throws and
  `listTools()` skips servers it cannot reach — one server being down must not cost the model the
  others' tools, or take the page down.
- Build every `RestClient` with `RestClient.builder()`, not an injected `RestClient.Builder`:
  `spring-boot-starter-webmvc` brings only the server side, so no such bean exists and asking for
  one stops the whole context from starting. **That absent bean is also why the observation
  registry must be passed by hand**: `RestClient.builder()` starts at `ObservationRegistry.NOOP`
  and there is no `spring-boot-restclient` module to auto-configure it back, so every builder here
  takes an injected `ObservationRegistry` and calls `.observationRegistry(...)`. Drop that call and
  the client records no span and sends no `traceparent` — the outbound call lands in Tempo as a
  parentless root trace with nothing linking it to the request that caused it. `McpConnections
  .from(...)` and the three `GoogleClientConfiguration` beans all thread it through, and
  `McpModuleConfiguration` injects it as a hard dependency rather than defaulting to `NOOP`, so the
  day the bean disappears is loud. `ObservationRegistry` is a third-party library type, not another
  module, so this does not widen `mcp`'s `allowedDependencies = {}` — `ModularityTests` proves it.

**UI**: `!` opens the server picker, `!/` the tool picker (grouped by server). `/` opens nothing — a
tool belongs to a server, and choosing one without saying which server was always ambiguous.
Clicking a tool **pre-fills** the composer with `!<server>/<tool> ` and shows that tool's call
syntax beside the same argument form as before; nothing runs on the click, including for a tool
that takes no arguments. `!<server>/<tool> name=value name="two words"` submitted from the composer
runs the tool instead of being sent to the model — parsed, validated against the tool's own schema
and coerced by declared type in `chat.js`, with an unresolved server, an unknown tool, an unknown
argument name or a missing required one reported in the palette rather than forwarded. Only a line
with a `/` after the `!` is intercepted, so an ordinary message that opens with `!` still sends.

### Logging to OpenTelemetry is hand-written too

Boot auto-configures an `SdkLoggerProvider` and a batch `LogRecordProcessor` — those two only,
gated on `@ConditionalOnEnabledOpenTelemetry` (`management.opentelemetry.enabled`,
`matchIfMissing=true`) — and installs **no Logback appender**, so log export looks configured and
exports nothing. The **exporter is a third thing and is not unconditional**: it needs an endpoint
property that Boot 4.1 gives no default for (see the constraints below). The documented appender,
`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, has **no released version**
(61 published, every one `-alpha`), so `audit/logging/` carries a small appender written against
`io.opentelemetry.api.logs` — GA since 1.27.0, compile-scope already, and documented as the API for
exactly this.

`OpenTelemetryLogRecordAppender` **must keep mapping `event.getKeyValuePairs()`**: the whole
application logs structured facts through `log.atInfo().addKeyValue(...)`, so dropping them
leaves Loki with message bodies and nothing to query on. `OpenTelemetryLoggingBridge` attaches
it to the root logger programmatically (no `logback-spring.xml`), which is why records logged
before the context is ready are not exported.

Four things in `append()` are load-bearing and easy to undo by tidying:
- **`appendMdc` runs before `appendKeyValuePairs`, and the order is the fix.** Both write through
  `setAttribute`, an equal `AttributeKey` replaces what was there, and the ~50 `addKeyValue` call
  sites are what the dashboards query on. Swap them and a colliding MDC key wins silently.
- **`appendMdc` skips `traceId`/`spanId`.** `setContext()` already carries them natively, and that
  is what produces the `trace_id`/`span_id` Grafana's derived field matches; mapping the MDC copies
  too ships every id twice.
- **Records whose logger name starts with `io.opentelemetry.` return early.** `jul-to-slf4j` plus
  Boot's `SLF4JBridgeHandler` route the exporter's own failure records here, so exporting them
  feeds the failing pipeline back into itself.
- **Stack traces are capped at `MAX_STACKTRACE_CHARACTERS` (16 384).** Loki's effective
  `max_structured_metadata_size` is 64 KB and it rejects the **whole entry** with a 400 rather than
  trimming the field, so an untruncated cause chain costs the error line entirely. A constant, not
  a property: the cap belongs to the backend and this is the only class that knows which field is
  at risk. 16 K rather than just under 64 K because the limit counts *bytes* while this counts
  *chars*. If `observability/loki/loki-config.yaml` ever changes that limit, revisit the constant.

## Constraints that will bite

- **Do not modify `pom.xml`** — standing project policy, still intact. The unused `spring-ai-spring-boot-testcontainers` dependency and the empty Initializr `<licenses>`/`<developers>`/`<scm>` placeholders stay as they are. Both the MCP module and the OTLP log bridge were built to hold this line; neither needs a dependency. **Never introduce an alpha/beta/snapshot artifact *directly*** — check the registry for a released line first, and prefer a GA API already on the classpath over a pre-release one. The rule is about what this project *declares*, not what resolves: `io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha` is already on the runtime classpath, pulled in transitively by `io.micrometer:micrometer-registry-otlp` (verify with `./mvnw dependency:tree`), and it has **no GA line at all** — every published version is `-alpha`. It is stated here so the next reader does not spend an afternoon hunting a released replacement that does not exist, or conclude the policy was broken.
- **Jackson 3**: Boot 4 auto-configures `tools.jackson.databind.ObjectMapper`. There is **no** Jackson 2 `ObjectMapper` bean. Annotations still come from `com.fasterxml.jackson.annotation` (see `StreamEvent`).
- **An assistant prefill must not end in whitespace, and no unit test will tell you.** Anthropic answers one with a flat `400 invalid_request_error: "messages: final assistant content cannot end with trailing whitespace"` — a message that mentions neither the prefill nor the fix. A mocked `ChatModel` accepts any prefill string, so `ChatClientVisionTest` passed while the real receipt import 400'd; it took running the app against the real API to find. `ChatClientVision` now calls `.stripTrailing()` on every prefill, because no caller can ever *want* that whitespace, and `ReceiptPrompt.PREFILL` ends at the ` ```json ` fence with the newline left for the model to write. Related, same corner: an assistant turn with **empty** content is also a 400, so a blank prefill must omit the turn rather than send an empty one.
- **The prefill is model-gated, and moving off Haiku breaks the receipt parser silently.** Anthropic rejects prefills with a 400 on Opus and Sonnet 4.6 and later; Haiku 4.5 still accepts them, and `spring.ai.anthropic.chat.model=claude-haiku-4-5` is what makes `groceries_import_receipt` work at all. Changing that property is a runtime break, not a compile or test one — the unit tests only pin the shape of the `Prompt`, not what the provider does with it. Spring AI's own mapping was verified by reading `AnthropicChatModel.createRequest` bytecode: `MessageType.ASSISTANT` → `addAssistantMessage`, `UserMessage.getMedia()` → `addUserMessageOfBlockParams`, `getStopSequences()` → `stopSequences`.
- **`ChatClientRequestSpec.options(...)` takes a *builder*, not built options** — there is no `options(ChatOptions)` overload. And `stopSequences`/`maxTokens` are not on `AnthropicChatOptions.AbstractBuilder`; they are inherited from `ChatOptions.Builder`, so `javap` on the Anthropic builder alone shows neither.
- **Never use `.user(...)` on a call that carries a prefill.** `DefaultChatClient` renders system → `messages(...)` → `user(...)`, so a `.user()` call lands *after* the trailing assistant turn and stops it being a prefill. `ChatClientVision` builds the whole list and passes it to `.messages(...)` only.
- **Images are attached to the current turn and never replayed in history.** `chat.context-window-size=0` replays the *entire* history every turn, so re-sending every photograph would multiply a long conversation's cost by the size of its album. What survives instead is `AttachmentNotes` writing `[attached images: #12 (image/jpeg)]` into the model's copy of the text — never into what is stored — which is the only way the model can name an image for `groceries_import_receipt`, since MCP tool arguments carry no conversation context.
- **Image caps live in `ChatProperties` as `@DefaultValue`s and in no properties file**, deliberately: the test and main `application.properties` shadow rather than merge, so a value written into only one would apply in one run mode and silently not the others. The browser downscales to 1568px/JPEG q0.8 in `chat.js` before upload — server-side resizing would need an image library, and the pom is frozen.
- **Taking a photo uses two different mechanisms, and neither one covers both targets.** The camera button routes on `matchMedia('(pointer: coarse)')`: a phone gets a file input with `capture="environment"`, which hands off to the OS camera app, and a desktop gets an in-page `getUserMedia` preview. The reason is the **secure-context rule**, and it is not negotiable — `navigator.mediaDevices` is `undefined` outright on a plain-http origin. Measured against this app's own exposure: `http://100.114.140.32:8080` (the tailnet address the phone uses, per `compose.yaml`) reports `isSecureContext = false` and has no `mediaDevices`, while `http://localhost:8080` is a secure origin and does. So the phone *cannot* use the in-page camera and does not need to; the desktop cannot use `capture=` because Firefox ignores it, and does. Serving the tailnet over HTTPS (`tailscale cert`/`serve`) would let both use either, and nothing here depends on that happening.
- **The canvas re-encode is what makes an iPhone photo work at all**, not just a size optimisation. The camera writes HEIC, which neither the endpoint's media-type allowlist nor Claude accepts; drawing to a canvas and reading it back as `image/jpeg` converts it on the way out. That is why both file inputs declare `accept="image/*"` rather than the four types the server takes — narrowing it would hide the user's own photos from the iOS picker.
- **This is not WebFlux.** `Flux` appears only where Spring AI hands one back, inside `ChatClientAssistant`. Everything runs on Servlet MVC with virtual threads (`spring.threads.virtual.enabled=true`, `spring.mvc.async.request-timeout=5m`). Do not re-expose reactive types.
- **`spring.jpa.hibernate.ddl-auto=update` is mandatory**: Boot does not treat file-based H2 as "embedded", so the default would be `none`; `update` also creates Modulith's `EVENT_PUBLICATION` table.
- **Never add `AUTO_SERVER=TRUE` to the H2 URL.** H2 2.x throws `Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"` while opening the database — mixed mode needs the JVM shutdown hook that `DB_CLOSE_ON_EXIT=FALSE` disables. Keep `DB_CLOSE_ON_EXIT=FALSE` (Hikari and Boot own the connection lifecycle); the `/h2-console` is in-process and never needs server mode.
- **`/h2-console` is unreachable under compose and no property fixes it.** It answers `200` with a 556-byte `notAllowed.jsp` for every caller, host `curl` included. H2's servlet guard is a bare `InetAddress.isLoopbackAddress()` on the remote address, and rootless podman re-originates every inbound connection through its userspace port forwarder, so the app sees `10.89.x.x` and never `127.0.0.1`. Use a host-side `spring-boot:run`/`spring-boot:test-run` instead — do not "fix" it by disabling the guard or adding a proxy header.
- **The app can never see a real client IP under compose.** Same re-origination, and no `server.forward-headers-strategy` is set, so there is no `X-Forwarded-For` to recover it from either. **Do not write an in-app IP allowlist** — it would see one address for the entire world and could only allow everyone or block everyone. Access scoping lives in the host firewall (`ufw`), which is where the real source address still exists; README's *Exposure and access* has the rule form.
- **There is no authentication anywhere, by design.** Every chat is readable and deletable and every MCP tool — including Gmail reads and calendar deletes — is invocable by anyone who can reach `:8080`. Treat that as a fixed premise when changing endpoints: a new endpoint inherits it, and nothing in the codebase will stop a caller.
- **`spring.ai.retry.*` does not apply to Anthropic in Spring AI 2.0.** Retries are SDK-internal via `spring.ai.anthropic.max-retries`, which lives in `observability.properties` (not `application.properties`) because the test classpath shadows the latter. There is no app-level retry.
- **Anthropic SDK exceptions propagate unwrapped**, so `AnthropicErrorClassifier` walks the cause chain to classify them.
- **Publishing an event from a non-transactional thread needs an explicit transaction.** `@ApplicationModuleListener` is `AFTER_COMMIT`, so a publish with no transaction in scope is **silently dropped**: the registry row is written, the listener never runs, and the only symptom is a "Marking stale publications … as failed" line a minute later. `AssistantErrorPublisher` (streaming catch block on a virtual thread) and `ToolEventPublisher` (tools invoked straight from the HTTP endpoint) both wrap the publish in a `REQUIRES_NEW` `TransactionTemplate` for this reason. Any new publisher outside a `@Transactional` service needs the same.
- **`Scenario` hides that bug.** `Scenario.stimulate(Runnable)` runs its stimulus inside a transaction, so a test written that way passes with or without the wrapper. `McpModuleEventTests` calls the service directly and asserts on the *auditor's counter* — the registry row exists either way, but the counter only moves if the listener actually ran.
- **Telemetry config that silently produces an empty Grafana**: `management.tracing.sampling.probability` defaults to `0.1` (set to `1.0` here); timers publish a single `+Inf` bucket unless `management.metrics.distribution.percentiles-histogram.*` asks (measured: 69 buckets with it, 1 without — `histogram_quantile` over one bucket is not empty, it is meaningless), so every p95 panel would read nonsense; and `otel-lgtm` keeps Grafana/Loki/Prometheus/Tempo under `/data`, so without the `lgtm-data` volume every restart wipes the lot.
- **`src/test/resources/application.properties` shadows the main one, it does not merge.** `target/test-classes` precedes `target/classes` and Boot resolves `classpath:/application.properties` to the first match only, so `spring-boot:test-run` (test classpath) sees *only* the test file. Three escape hatches, and a new setting has to land in one of them or it applies in production and nowhere else. `src/main/resources/pricing.properties`, imported by *both* files, holds the `app.llm.pricing.prices[...]` entries and nothing else — a price written into either `application.properties` would be invisible to `spring-boot:test-run`, which is the mode the LLM cost dashboard is looked at in. `src/main/resources/observability.properties`, imported by *both* files, now holds sampling, the export step, the histogram lines, `management.metrics.distribution.maximum-expected-value.*`, `spring.task.execution.propagate-context` **and** `spring.ai.anthropic.max-retries` plus the three `spring.ai.chat.observations.*` switches; and `TestClaudePersonalAssistantApplication.DEV_RUN_OVERRIDES`, which is no longer just the export switches and the 5m timeout — it also restores `management.opentelemetry.enabled=true`, the file datasource URL, `ddl-auto=update`, the H2 console, the three `spring.modulith.events.*` settings and the whole `mcp.servers[0]` block. `withDevRunOverrides` keys on `substring(0, indexOf('=') + 1)`, so a value containing `=` or `;` is fine, but every added key prefix must stay distinct.
- **Boot 4.1 gives OTLP traces and logs no default endpoint, and the failure is silent.** `OtlpLoggingProperties`/`OtlpTracingProperties` declare a `@Nullable endpoint` with no default, their `ConnectionDetails` beans are `@ConditionalOnProperty` on it and the exporters `@ConditionalOnBean` on those — so with none set the provider is still built, the bridge still attaches, 100% of spans are still recorded, and every signal lands in a `Noop` exporter with no `ConnectException` and nothing to grep for. Metrics look fine only because Micrometer's `OtlpConfig` hardcodes `http://localhost:4318/v1/metrics`. `application.properties` sets `management.opentelemetry.{logging,tracing}.export.otlp.endpoint` explicitly. Note `OTEL_EXPORTER_OTLP_ENDPOINT` in `.env` does **not** work: the post-processor reads `System::getenv`, never the Spring `Environment` that `spring.config.import=optional:file:.env` feeds — but a real env var (compose) still wins, since the mapping is installed with `addFirst()`.
- **`management.opentelemetry.enabled`, not the export switches, is what turns OpenTelemetry off.** `OpenTelemetryLoggingAutoConfiguration` is gated on `@ConditionalOnEnabledOpenTelemetry` (`matchIfMissing=true`) and its `SdkLoggerProvider` needs only `@ConditionalOnBean(Resource.class)`, so the three `*.export.*.enabled=false` lines silence exporters and leave provider, processor and appender fully in place. Until `management.opentelemetry.enabled=false` was added to the test file, every cached test context really did attach the bridge to the JVM-global Logback root logger. `TestClaudePersonalAssistantApplication` turns it back on for `spring-boot:test-run`.
- **`spring.task.execution.propagate-context=true` is what makes one chat turn one trace.** Boot's context-propagating `TaskDecorator` is off by default, MVC async runs on `applicationTaskExecutor`, and `ChatStreamController` hands the whole assistant call to a `StreamingResponseBody` — so without it a turn arrives in Tempo as three unrelated root traces, every line logged on a `task-N` thread carries no `trace_id`, and `streamTurn`/`assistant.stream` get no exemplars. Anything else moved onto a task executor inherits this.
- **`management.otlp.logging.export.enabled` is deprecated at `level=error` in Boot 4** — it no longer binds, so it reads as "log export disabled" while logs keep exporting. The property that works is `management.logging.export.otlp.enabled`.
- **A container clock behind the host silently drops every metric sample.** Samples carry the JVM clock; Prometheus in a lagging podman VM rejects them as too far in the future. Signature: `otelcol_exporter_send_failed_metric_points_total` rising exactly in step with `otelcol_receiver_accepted_metric_points_total`, while traces and logs still work. Repair with `podman machine ssh <machine> "sudo date -s @$(date +%s)"` — it is not a config problem.
- **Per-module metrics are explicit, not `@Timed`.** The annotation needs an AOP starter and the pom is frozen. Each module owns its own constants class — a module importing another's metric names would be depending on it — but **only `assistant` and `chat` actually emit a boundary timer**: `MODULE_OPERATION = "app.module.operation"` exists in `assistant/config/AssistantMetrics.java` and `chat/config/ChatMetrics.java` and nowhere else. `mcp/domain/McpMetrics.java` declares `mcp.tool.invocations` and the `mcp.tool` timer instead, and `audit/AuditMetrics.java` declares **no timer at all**, only the five counters its listeners increment. So the `module` tag distinguishes two modules, not four, and the Modules dashboard's boundary panels cover those two while MCP gets its own pair. If you add a boundary timer to `mcp` or `audit`, name it `app.module.operation` and tag it with the module and it joins those panels unchanged — but do not assume the convention is already universal when reading a dashboard or writing a query.
- **Cost is computed in Prometheus, never in Java, and the join is held together by tag names.** `LlmTokenPriceMeterBinder` publishes `llm.token.price.usd.per.mtok` tagged `gen_ai.response.model` / `gen_ai.token.type` — deliberately the same tag names Spring AI puts on `gen_ai.client.token.usage`, so `* on (gen_ai_response_model, gen_ai_token_type) group_left()` joins them with no `label_replace()`. Rename either tag and every cost panel needs rewriting. Three things around it are easy to undo: the price map is keyed by the **resolved** model (`claude-haiku-4-5-20251001`), not the configured alias (`claude-haiku-4-5`) — `gen_ai_response_model` carries the dated form, verified live; `Gauge.builder(...).strongReference(true)` is mandatory because the value supplier closes over a loop-local and Micrometer holds gauge sources weakly, so without it every price goes `NaN` at the first GC and the series goes *stale* rather than missing; and `gen_ai_token_type="total"` is its own series alongside `input`/`output`, so any sum that does not filter it double-counts. Prices live in `pricing.properties` only.
- **Anthropic's cached tokens are not in the token counter, and caching is currently off.** `AnthropicChatModel.getDefaultUsage` maps `inputTokens()` straight to `promptTokens` and computes `totalTokens` as `input + output`; `cacheReadInputTokens`/`cacheWriteInputTokens` ride along on `DefaultUsage` but `ModelUsageMetricsGenerator` never reads them, so they reach Tempo as `gen_ai.usage.cache_creation.input_tokens` / `gen_ai.usage.cache_read.input_tokens` and Prometheus never. That costs nothing today because `AnthropicCacheOptions` defaults to `AnthropicCacheStrategy.NONE` and nothing overrides it — confirmed live, both cache figures `0`. **Enable prompt caching and the cost dashboard silently understates spend** until the cache tokens are metered separately. `AnthropicTokenAccountingVerificationTest` is the guard; it calls the real API and skips without a key in `.env`.
- **`service_name` is the only Loki index label.** `detected_level`, `trace_id`, `span_id` and every `addKeyValue` fact are *structured metadata* — they appear inside a result's `stream` map, which is what makes them look like labels, but a selector cannot match them (`{detected_level="info"}` returns zero streams, and `GET /loki/api/v1/labels` answers `["service_name"]`). So `{tool="x"}` selects nothing and the query has to be `{service_name="claude-personal-assistant"} | tool="x"`. Keep it that way: `chatId`/`conversationId` are unbounded, and promoting one to a label creates a Loki stream per conversation forever. Dashboard panels and any new query must use the filter form.
- **Anything under `observability/` is mounted twice.** `compose.yaml` bind-mounts it and `TestcontainersConfiguration` copies it, so a change has to land in both or it applies to one run mode only. A new `dashboards/*.json` is already covered (the dashboards entry copies the whole directory); a new *single file* — a datasource, a backend config — needs a mount in `compose.yaml` **and** a `withCopyFileToContainer` call. Currently five such single files: `loki/loki-config.yaml`, `grafana/provisioning/datasources/app-datasources.yaml`, `grafana/provisioning/alerting/llm-cost-alerts.yaml`, `prometheus/prometheus.yaml` and `prometheus/llm-cost-rules.yaml`. The alerting file is mounted as a *file*, not as its directory, because bind-mounting `provisioning/alerting/` would mask the image's own contents rather than adding to them — and a malformed alerting file is not a degraded Grafana but no Grafana, since the provisioning module fails at startup and the container never reports ready. Also: a compose bind mount whose source does not exist makes podman create a **directory** at that path, which silently gives Grafana no datasource and takes Loki down outright, since the image reads `loki-config.yaml` at startup.
- **`mcp.servers[n]` indices must stay contiguous from 0.** Boot's `IndexedElementsBinder` binds `[0]`, `[1]`, … and stops at the first missing index, never looking past a gap — so commenting out `[0]` while `[1]`/`[2]` remain binds **nothing**, with no error: the list falls back to the single local default and the later entries vanish. Uncomment or renumber the whole block together. `McpConnections` logs one line per server it wired plus a count, which is what makes this visible at startup.
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
