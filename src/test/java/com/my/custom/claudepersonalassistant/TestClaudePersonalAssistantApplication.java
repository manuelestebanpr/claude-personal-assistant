package com.my.custom.claudepersonalassistant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;

/**
 * Development entry point: the application plus a Testcontainers-managed Grafana LGTM stack.
 * Started by {@code ./mvnw spring-boot:test-run}.
 */
public class TestClaudePersonalAssistantApplication {

    /**
     * {@code src/test/resources/application.properties} turns OTLP export off so JUnit runs stay
     * quiet and keep a {@code SimpleMeterRegistry} to assert against. {@code spring-boot:test-run}
     * launches from the <em>test</em> classpath, so it inherited that file too — which silently
     * defeated the whole point of this entry point: the LGTM container came up and received log
     * records only, while metrics and traces were dropped with no warning and every dashboard
     * panel read "No data".
     *
     * <p>Command-line arguments outrank {@code application.properties}, and only this
     * {@code main} is affected, so the test suite keeps the behaviour it asserts on.
     */
    static final String[] DEV_RUN_OVERRIDES = {
            "--management.otlp.metrics.export.enabled=true",
            "--management.tracing.export.enabled=true",
            "--management.logging.export.otlp.enabled=true",
            // The three switches above only govern the exporters. The test file also sets
            // management.opentelemetry.enabled=false, which is what keeps JUnit from attaching the
            // Logback bridge at all — leaving it off here would shut down the whole SDK and no
            // amount of exporter switches would put a single log record on the wire.
            "--management.opentelemetry.enabled=true",
            // The test file caps async requests at 10s to keep the suite fast. This is an
            // interactive run, where that truncates any answer Claude takes longer than 10s to
            // stream — put back the production timeout.
            "--spring.mvc.async.request-timeout=5m",
            // Everything below restores a line of src/main/resources/application.properties that
            // this run never sees, because the test application.properties shadows that file rather
            // than merging with it. Each one is silently absent, not defaulted to something sane.
            // Without the datasource url, Boot generates a fresh in-memory H2 per boot: an
            // interactive session loses every chat on restart and ./data is never written.
            "--spring.datasource.url=jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE",
            // The test file says create-drop, which would wipe that file database on every start.
            // update is also what creates Modulith's EVENT_PUBLICATION table.
            "--spring.jpa.hibernate.ddl-auto=update",
            // The test file switches the console off; a dev run inspecting persisted chats needs
            // it, and without this /h2-console answers 404.
            "--spring.h2.console.enabled=true",
            // Modulith event settings: none of these are on by default, so an event left
            // incomplete by a restart would never be retried and a listener hung in PROCESSING
            // would surface nowhere.
            "--spring.modulith.events.republish-outstanding-events-on-restart=true",
            "--spring.modulith.events.completion-mode=update",
            "--spring.modulith.events.staleness.processing=10m",
            // With no mcp.servers[] entry bound at all the model is offered zero tools — and the
            // default "assume one local entry" fallback only applies to the property being unset in
            // a file this run does read. The whole block must be passed together: Boot's indexed
            // binder stops at the first missing index, so a partial block binds nothing.
            "--mcp.servers[0].id=local",
            "--mcp.servers[0].name=Claude Personal Assistant",
            "--mcp.servers[0].base-url=http://localhost:8080",
            "--mcp.servers[0].endpoint=/mcp",
            "--mcp.servers[0].protocol=STATELESS",
            // The Google tools are the other half of the tool palette, and the test file names
            // google.workspace.* nowhere — so without these a fully populated .env still yields
            // one tool, because GoogleWorkspaceProperties.enabled defaults to false and
            // GOOGLE_WORKSPACE_ENABLED cannot relax-bind to it from an ordinary properties source.
            // The placeholders resolve against the .env this run now imports; with no .env the
            // defaults switch the module off exactly as before.
            "--google.workspace.enabled=${GOOGLE_WORKSPACE_ENABLED:false}",
            "--google.workspace.client-id=${GOOGLE_CLIENT_ID:}",
            "--google.workspace.client-secret=${GOOGLE_CLIENT_SECRET:}",
            "--google.workspace.refresh-token=${GOOGLE_REFRESH_TOKEN:}"
    };

    public static void main(String[] args) {
        SpringApplication.from(ClaudePersonalAssistantApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(withDevRunOverrides(args));
    }

    /**
     * Appends the overrides the caller did not already set. A key repeated on the command line is
     * collected into a list and rendered comma-joined, which would turn {@code true} into
     * {@code true,true} and fail to bind.
     */
    static String[] withDevRunOverrides(String[] args) {
        List<String> merged = new ArrayList<>(List.of(args));
        for (String override : DEV_RUN_OVERRIDES) {
            String key = override.substring(0, override.indexOf('=') + 1);
            if (merged.stream().noneMatch(arg -> arg.startsWith(key))) {
                merged.add(override);
            }
        }
        return merged.toArray(String[]::new);
    }

}
