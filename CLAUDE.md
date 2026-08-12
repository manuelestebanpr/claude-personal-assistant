# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Spring Boot 4.1.0 application (Java 25) named `claude-personal-assistant`, generated from Spring Initializr. It is currently a bare skeleton: the only application code is the `@SpringBootApplication` entry point plus a Testcontainers dev-service config — no controllers, services, repositories, or domain code exist yet.

This directory is not a git repository (no `.git`). Git-based workflows (`git log`, branches, etc.) won't work here until one is initialized.

## Build and test commands

Use the Maven wrapper (no local Maven install required):
- Build: `./mvnw clean install` (or `./mvnw compile`)
- Run all tests: `./mvnw test`
- Run a single test class: `./mvnw test -Dtest=ClaudePersonalAssistantApplicationTests`
- Run a single test method: `./mvnw test -Dtest=ClaudePersonalAssistantApplicationTests#contextLoads`
- Run the app: `./mvnw spring-boot:run`
- Run the app with the Testcontainers dev service attached (auto-starts the Grafana LGTM observability stack): `./mvnw spring-boot:test-run`, which launches via `TestClaudePersonalAssistantApplication` instead of the normal main class.

## Architecture

- **Package root**: `com.my.custom.claudepersonalassistant`
- **Entry point**: `ClaudePersonalAssistantApplication` — standard `@SpringBootApplication`.
- **Dev-mode entry point**: `TestClaudePersonalAssistantApplication` (lives under `src/test`) — boots the real application with `TestcontainersConfiguration` imported, so local dev runs get a live observability backend without a separate `docker-compose` setup.
- **Testcontainers dev/test service**: `TestcontainersConfiguration` is a test-scope `@TestConfiguration` declaring an `LgtmStackContainer` (`grafana/otel-lgtm:latest`) as a `@ServiceConnection` — this wires OpenTelemetry export automatically for both `./mvnw test` and the dev-mode entry point above. Pin the same image tag in production as used here.

### Dependency stack and what it implies for future code

Parent POM: `spring-boot-starter-parent:4.1.0`. From the declared starters in `pom.xml`:
- `spring-boot-starter-webmvc` + `spring-boot-starter-thymeleaf` — this is a server-rendered MVC app, not a REST-only or reactive service.
- `spring-boot-starter-data-jpa` + `h2` (runtime) + `spring-boot-h2console` — JPA persistence backed by H2, with the H2 web console available.
- `spring-ai-starter-model-anthropic` (`spring-ai-bom:2.0.0`) — Spring AI's Claude/Anthropic chat model integration is on the classpath for AI-assisted features.
- `spring-modulith-starter-core` + `spring-modulith-starter-jpa` (`spring-modulith-bom:2.1.0`) — the app is expected to grow as Spring Modulith modules (one package per module under the root package, with JPA-backed event publication registry), not a flat layered structure.
- `spring-boot-starter-opentelemetry` — tracing/metrics, exported to the Testcontainers-provided Grafana LGTM stack in dev/test.
- `lombok` — annotation processor is already wired into both the `default-compile` and `default-testCompile` executions of `maven-compiler-plugin`.
- Test scope mirrors the main starters with `-test`/`-testcontainers` variants, plus `testcontainers-grafana` and `testcontainers-junit-jupiter`.

### pom.xml quirk

The POM contains intentionally empty `<license>` and `<developers>` overrides to block unwanted inheritance from the parent POM — don't remove these unless the parent is also being changed.
