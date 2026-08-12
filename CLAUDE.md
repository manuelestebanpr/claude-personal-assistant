# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Server-rendered, multi-conversation chat backed by Claude (Anthropic) via Spring AI. Spring Boot 4.1 / Java 25, WebMVC + Thymeleaf, token-by-token streaming over the servlet `OutputStream`, per-chat history in file-based H2, OpenTelemetry export to a Grafana LGTM stack. Single user — no auth, no user management.

`README.md` is the long-form reference (streaming protocol, error-classification matrix, observability, running instructions). This file carries what is needed to change code correctly.

## Build and test commands

Use the Maven wrapper:
- Build: `./mvnw clean install` (compile only: `./mvnw compile`)
- All tests: `./mvnw test`
- Single class: `./mvnw test -Dtest=ChatModuleIntegrationTests`
- Single method: `./mvnw test -Dtest=ChatModuleIntegrationTests#happyPathStreamPersistsBothMessagesAndRehydratesOnReopen`
- Run with the observability stack (Testcontainers `grafana/otel-lgtm`, needs Docker/podman): `./mvnw spring-boot:test-run` — boots via `TestClaudePersonalAssistantApplication`, not the main class
- Run plain (no containers, OTLP export warnings are harmless): `./mvnw spring-boot:run`
- Production-style: `podman compose up --build` (Grafana on `:3000`, app on `:8080`, H2 file in `./data`)

`ANTHROPIC_API_KEY` comes from a git-ignored `.env` (see `.env.example`), imported through `spring.config.import=optional:file:.env[.properties]`. Tests use a dummy key from `src/test/resources/application.properties`.

Tests that boot the full context (`ClaudePersonalAssistantApplicationTests`) require a container runtime. Slice tests (`@WebMvcTest`) and `@ApplicationModuleTest` tests do not.

## Architecture

Three Spring Modulith modules, direct subpackages of `com.my.custom.claudepersonalassistant`:

```
audit  ──▶ chat ──▶ assistant ──▶ Spring AI / Anthropic
                      ▲
                      └── allowedDependencies = {}
```

- **`chat`** — conversation lifecycle, persistence, web UI, streaming endpoint. Allowed to depend only on `assistant`.
- **`assistant`** — Spring AI / Anthropic integration. Depends on **nothing**; Spring AI types never cross `AssistantClient`.
- **`audit`** — observability listeners consuming events from the other two.

Each module declares its `allowedDependencies` in `package-info.java`, and `ModularityTests` enforces it with `ApplicationModules.verify()` plus an explicit assertion that the module set is exactly `{assistant, audit, chat}` (so a stray top-level package cannot silently become a fourth module). **A boundary violation fails the build — it is not a convention.**

**Package convention**: API types (interfaces, records, events, enums) live at the module root; internals live in nested subpackages (`web`, `service`, `persistence`, `config`, `client`, `error`, `logging`). Nested packages are invisible to other modules.

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

## Constraints that will bite

- **Do not modify `pom.xml`** — standing project policy. The unused `spring-ai-spring-boot-testcontainers` dependency and the empty Initializr `<licenses>`/`<developers>`/`<scm>` placeholders stay as they are.
- **Jackson 3**: Boot 4 auto-configures `tools.jackson.databind.ObjectMapper`. There is **no** Jackson 2 `ObjectMapper` bean. Annotations still come from `com.fasterxml.jackson.annotation` (see `StreamEvent`).
- **This is not WebFlux.** `Flux` appears only where Spring AI hands one back, inside `ChatClientAssistant`. Everything runs on Servlet MVC with virtual threads (`spring.threads.virtual.enabled=true`, `spring.mvc.async.request-timeout=5m`). Do not re-expose reactive types.
- **`spring.jpa.hibernate.ddl-auto=update` is mandatory**: Boot does not treat file-based H2 as "embedded", so the default would be `none`; `update` also creates Modulith's `EVENT_PUBLICATION` table.
- **`spring.ai.retry.*` does not apply to Anthropic in Spring AI 2.0.** Retries are SDK-internal via `spring.ai.anthropic.max-retries`. There is no app-level retry.
- **Anthropic SDK exceptions propagate unwrapped**, so `AnthropicErrorClassifier` walks the cause chain to classify them.
- **Publishing an event from a non-transactional thread needs an explicit transaction.** `@ApplicationModuleListener` is `AFTER_COMMIT`; `AssistantErrorPublisher` wraps the publish in a `REQUIRES_NEW` `TransactionTemplate` because the streaming catch block runs on a virtual thread with no bound transaction — without it the event is silently dropped.
- **`spring-boot-docker-compose` is deliberately absent**: it shells out to a literal `docker` binary, which breaks under podman.
- Tests deliberately declare no `spring.datasource.url`, so each test context gets its own in-memory H2 and concurrently cached contexts cannot drop each other's tables.

## Events

| Event | Published by | Consumed by |
|---|---|---|
| `ChatCreatedEvent` | `DefaultConversationService.create()` | `ChatLifecycleAuditor` → `assistant.chats.created` |
| `ChatDeletedEvent` | `DefaultConversationService.delete()` | `ChatLifecycleAuditor` → `assistant.chats.deleted` |
| `AssistantErrorEvent` | `AssistantErrorPublisher` | `AssistantErrorAuditor` → `assistant.stream.errors{classification,error.type}` |
