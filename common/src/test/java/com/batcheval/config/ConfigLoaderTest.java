package com.batcheval.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest {

    @Test
    void readsCfgFile(@TempDir Path tempDir) throws Exception {
        Path cfg = tempDir.resolve("test.cfg");
        Files.writeString(cfg, """
                # comment
                API_PORT=9001
                DATABASE_URL=jdbc:sqlite:custom.db
                """);

        var values = ConfigLoader.readCfgFile(cfg);
        assertThat(values).containsEntry("API_PORT", "9001");
        assertThat(values).containsEntry("DATABASE_URL", "jdbc:sqlite:custom.db");
        assertThat(values).doesNotContainKey("# comment");
    }

    @Test
    void appConfigFromMapUsesDefaultsForMissingKeys() {
        AppConfig config = AppConfig.from(java.util.Map.of("API_PORT", "9000"));
        assertThat(config.apiPort()).isEqualTo(9000);
        assertThat(config.apiPrefix()).isEqualTo("/v1");
    }
}
