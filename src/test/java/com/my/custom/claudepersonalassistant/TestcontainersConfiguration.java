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

    @Bean
    @ServiceConnection
    LgtmStackContainer grafanaLgtmContainer() {
        // Copied rather than bind-mounted: Testcontainers copies before the entrypoint runs, which
        // is what Grafana provisioning needs, and it works the same on a remote Docker host.
        // Without this the container ships only its own dashboards, so the two this project
        // provisions exist under compose and nowhere else — telemetry arrives with nothing to
        // read it on.
        return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"))
                .withCopyFileToContainer(MountableFile.forHostPath(PROVIDER_HOST_PATH),
                        PROVIDER_CONTAINER_PATH)
                .withCopyFileToContainer(MountableFile.forHostPath(DASHBOARDS_HOST_PATH),
                        DASHBOARDS_CONTAINER_PATH);
    }

}
