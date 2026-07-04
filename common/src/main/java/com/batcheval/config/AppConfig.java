package com.batcheval.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record AppConfig(
        int apiPort,
        String apiPrefix,
        String databaseUrl,
        String awsRegion,
        URI awsEndpointUrl,
        String s3Bucket,
        String sqsQueueUrl,
        Duration downloadUrlTtl,
        int resultRetentionDays,
        URI promptEndpointUrl,
        Duration promptTimeout,
        int maxRetryAttempts,
        double retryBaseDelaySeconds,
        double retryMaxDelaySeconds,
        int workerMaxConcurrency,
        int workerPollWaitSeconds,
        int workerVisibilityTimeout,
        long maxFileSizeBytes,
        int maxRequestsPerFile,
        String priorityModel
) {
    /** Loads {@code config/batch-eval.cfg}, with env vars overriding file values. */
    public static AppConfig load() {
        return from(ConfigLoader.load());
    }

    /** @deprecated use {@link #load()} */
    @Deprecated
    public static AppConfig fromEnv() {
        return load();
    }

    static AppConfig from(Map<String, String> cfg) {
        return new AppConfig(
                getInt(cfg, "API_PORT", 8000),
                getString(cfg, "API_PREFIX", "/v1"),
                getString(cfg, "DATABASE_URL", "jdbc:sqlite:batch_eval.db"),
                getString(cfg, "AWS_REGION", "us-east-1"),
                getUri(cfg, "AWS_ENDPOINT_URL"),
                getString(cfg, "S3_BUCKET", "batch-eval-files"),
                getString(cfg, "SQS_QUEUE_URL", "http://localhost:4566/000000000000/batch-eval-jobs"),
                Duration.ofSeconds(getInt(cfg, "DOWNLOAD_URL_TTL_SECONDS", 900)),
                getInt(cfg, "RESULT_RETENTION_DAYS", 90),
                URI.create(getString(cfg, "PROMPT_ENDPOINT_URL", "http://localhost:9000/v1/evaluate")),
                Duration.ofSeconds(getInt(cfg, "PROMPT_TIMEOUT_SECONDS", 60)),
                getInt(cfg, "MAX_RETRY_ATTEMPTS", 5),
                getDouble(cfg, "RETRY_BASE_DELAY_SECONDS", 1.0),
                getDouble(cfg, "RETRY_MAX_DELAY_SECONDS", 60.0),
                getInt(cfg, "WORKER_MAX_CONCURRENCY", 4),
                getInt(cfg, "WORKER_POLL_WAIT_SECONDS", 20),
                getInt(cfg, "WORKER_VISIBILITY_TIMEOUT", 300),
                getLong(cfg, "MAX_FILE_SIZE_BYTES", 200L * 1024 * 1024),
                getInt(cfg, "MAX_REQUESTS_PER_FILE", 50_000),
                getString(cfg, "PRIORITY_MODEL", "meta-llama-3-8b-instruct")
        );
    }

    private static String getString(Map<String, String> cfg, String key, String defaultValue) {
        String value = cfg.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int getInt(Map<String, String> cfg, String key, int defaultValue) {
        String value = cfg.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static long getLong(Map<String, String> cfg, String key, long defaultValue) {
        String value = cfg.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value);
    }

    private static double getDouble(Map<String, String> cfg, String key, double defaultValue) {
        String value = cfg.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(value);
    }

    private static URI getUri(Map<String, String> cfg, String key) {
        String value = cfg.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return URI.create(value);
    }
}
