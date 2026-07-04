package com.batcheval.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads {@code config/*.cfg} files (Java properties format: {@code key=value}, {@code #} comments).
 */
public final class ConfigLoader {

    public static final String CONFIG_PATH_ENV = "BATCH_EVAL_CONFIG";
    public static final Path DEFAULT_CONFIG_PATH = Path.of("config", "batch-eval.cfg");

    private ConfigLoader() {}

    public static Map<String, String> load() {
        Path path = resolveConfigPath();
        Map<String, String> values = new HashMap<>();
        if (Files.isRegularFile(path)) {
            values.putAll(readCfgFile(path));
        }
        applyEnvOverrides(values);
        return Map.copyOf(values);
    }

    static Path resolveConfigPath() {
        String override = System.getenv(CONFIG_PATH_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return DEFAULT_CONFIG_PATH;
    }

    static Map<String, String> readCfgFile(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read config file: " + path.toAbsolutePath(), ex);
        }
        Map<String, String> result = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            result.put(name, properties.getProperty(name).trim());
        }
        return result;
    }

    private static final java.util.Set<String> CONFIG_KEYS = java.util.Set.of(
            "API_PORT", "API_PREFIX", "DATABASE_URL", "AWS_REGION", "AWS_ENDPOINT_URL",
            "S3_BUCKET", "SQS_QUEUE_URL", "UPLOAD_URL_TTL_SECONDS", "DOWNLOAD_URL_TTL_SECONDS",
            "RESULT_RETENTION_DAYS", "PROMPT_ENDPOINT_URL", "PROMPT_TIMEOUT_SECONDS",
            "MAX_RETRY_ATTEMPTS", "RETRY_BASE_DELAY_SECONDS", "RETRY_MAX_DELAY_SECONDS",
            "WORKER_MAX_CONCURRENCY", "WORKER_POLL_WAIT_SECONDS", "WORKER_VISIBILITY_TIMEOUT",
            "MAX_FILE_SIZE_BYTES", "MAX_REQUESTS_PER_FILE", "PRIORITY_MODEL"
    );

    private static void applyEnvOverrides(Map<String, String> values) {
        for (String key : CONFIG_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                values.put(key, value.trim());
            }
        }
    }
}
