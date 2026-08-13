package com.my.custom.claudepersonalassistant;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production datasource URL.
 *
 * <p>Every Spring test context deliberately omits {@code spring.datasource.url} and falls back to a
 * generated in-memory database, so an invalid production URL passes the entire suite and only
 * surfaces when the container boots. This test opens the real URL — relocated to a temp directory so
 * it never touches {@code ./data} — and therefore fails on rejected setting combinations such as
 * {@code AUTO_SERVER=TRUE} together with {@code DB_CLOSE_ON_EXIT=FALSE}.
 */
class ProductionDatasourceUrlTest {

    private static final Path MAIN_PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final String FILE_URL_PREFIX = "jdbc:h2:file:";

    @Test
    void productionDatasourceUrlOpensAConnection(@TempDir Path tempDir) throws Exception {
        String url = productionDatasourceUrl();
        assertThat(url).startsWith(FILE_URL_PREFIX);

        try (Connection connection = DriverManager.getConnection(relocate(url, tempDir), "sa", "")) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    private static String productionDatasourceUrl() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(MAIN_PROPERTIES)) {
            properties.load(in);
        }
        String url = properties.getProperty("spring.datasource.url");
        assertThat(url).as("spring.datasource.url in %s", MAIN_PROPERTIES).isNotBlank();
        return url;
    }

    /** Swaps the database file path for a temp one, keeping every {@code ;SETTING=VALUE} intact. */
    private static String relocate(String url, Path tempDir) {
        int firstSetting = url.indexOf(';');
        String settings = firstSetting < 0 ? "" : url.substring(firstSetting);
        return FILE_URL_PREFIX + tempDir.resolve("chat").toAbsolutePath() + settings;
    }
}
