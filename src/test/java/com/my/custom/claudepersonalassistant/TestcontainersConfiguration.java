package com.my.custom.claudepersonalassistant;

import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /** Same paths {@code compose.yaml} bind-mounts, so both ways of running show one Grafana. */
    private static final String PROVIDER_HOST_PATH =
            "observability/grafana/provisioning/dashboards/app-dashboards.yaml";
    private static final String PROVIDER_CONTAINER_PATH =
            "/otel-lgtm/grafana/conf/provisioning/dashboards/app-dashboards.yaml";
    private static final String DASHBOARDS_HOST_PATH = "observability/grafana/dashboards";
    private static final String DASHBOARDS_CONTAINER_PATH = "/var/lib/grafana/app-dashboards";
    private static final String DATASOURCES_HOST_PATH =
            "observability/grafana/provisioning/datasources/app-datasources.yaml";
    private static final String DATASOURCES_CONTAINER_PATH =
            "/otel-lgtm/grafana/conf/provisioning/datasources/app-datasources.yaml";
    private static final String LOKI_CONFIG_HOST_PATH = "observability/loki/loki-config.yaml";
    private static final String LOKI_CONFIG_CONTAINER_PATH = "/otel-lgtm/loki-config.yaml";
    private static final String PROMETHEUS_CONFIG_HOST_PATH = "observability/prometheus/prometheus.yaml";
    private static final String PROMETHEUS_CONFIG_CONTAINER_PATH = "/otel-lgtm/prometheus.yaml";
    private static final String PROMETHEUS_RULES_HOST_PATH = "observability/prometheus/llm-cost-rules.yaml";
    private static final String PROMETHEUS_RULES_CONTAINER_PATH = "/otel-lgtm/llm-cost-rules.yaml";
    private static final String ALERTING_HOST_PATH =
            "observability/grafana/provisioning/alerting/llm-cost-alerts.yaml";
    private static final String ALERTING_CONTAINER_PATH =
            "/otel-lgtm/grafana/conf/provisioning/alerting/llm-cost-alerts.yaml";

    @Bean
    @ServiceConnection
    LgtmStackContainer grafanaLgtmContainer() {
        // Copied rather than bind-mounted: Testcontainers copies before the entrypoint runs, which
        // is what Grafana provisioning needs, and it works the same on a remote Docker host.
        // Without this the container ships only its own dashboards, so the ones this project
        // provisions exist under compose and nowhere else — telemetry arrives with nothing to
        // read it on. The dashboards entry copies the whole directory, so a new *.json file needs
        // no change here; a new single-file mount in compose.yaml does.
        return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"))
                .withCopyFileToContainer(MountableFile.forHostPath(PROVIDER_HOST_PATH),
                        PROVIDER_CONTAINER_PATH)
                .withCopyFileToContainer(MountableFile.forHostPath(DASHBOARDS_HOST_PATH),
                        DASHBOARDS_CONTAINER_PATH)
                // The whole point of the app-owned Prometheus datasource is timeInterval: 15s.
                // Without this copy spring-boot:test-run falls back to the image's datasource file,
                // which has no `prometheus-15s` uid at all — so every panel on both dashboards
                // resolves to a missing datasource and the run mode that exists to show telemetry
                // shows nothing.
                .withCopyFileToContainer(MountableFile.forHostPath(DATASOURCES_HOST_PATH),
                        DATASOURCES_CONTAINER_PATH)
                // Overwrites the image's own loki-config.yaml, same as the compose bind mount, so
                // the retention settings are exercised here too rather than only in production —
                // a Loki that refuses to start on a bad compactor block must fail in the test run,
                // not first on the deployed stack.
                .withCopyFileToContainer(MountableFile.forHostPath(LOKI_CONFIG_HOST_PATH),
                        LOKI_CONFIG_CONTAINER_PATH)
                // Same wholesale-replacement trade as loki-config.yaml, and for the same kind of
                // reason: Prometheus has no --rules.file flag and the image's config declares no
                // rule_files, so the only way to add the llm:spend_usd recording rules is to
                // replace the config that references them.
                .withCopyFileToContainer(MountableFile.forHostPath(PROMETHEUS_CONFIG_HOST_PATH),
                        PROMETHEUS_CONFIG_CONTAINER_PATH)
                .withCopyFileToContainer(MountableFile.forHostPath(PROMETHEUS_RULES_HOST_PATH),
                        PROMETHEUS_RULES_CONTAINER_PATH)
                // A malformed alert file is not a degraded Grafana, it is no Grafana: the
                // provisioning module fails at startup and the container never becomes ready. So
                // this copy is also what makes `./mvnw spring-boot:test-run` a real check on the
                // file, rather than discovering it first on the deployed stack.
                .withCopyFileToContainer(MountableFile.forHostPath(ALERTING_HOST_PATH),
                        ALERTING_CONTAINER_PATH);
    }

}
