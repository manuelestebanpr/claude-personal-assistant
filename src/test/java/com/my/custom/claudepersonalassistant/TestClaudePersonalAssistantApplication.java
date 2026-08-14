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
            // The test file caps async requests at 10s to keep the suite fast. This is an
            // interactive run, where that truncates any answer Claude takes longer than 10s to
            // stream — put back the production timeout.
            "--spring.mvc.async.request-timeout=5m"
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
