package com.batcheval.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Response for job create and status endpoints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchJobResponse(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("file_id") UUID fileId,
        JobStatus status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("failed_at") Instant failedAt,
        Metrics metrics,
        @JsonProperty("error_message") String errorMessage
) {
    public BatchJobResponse {
        Objects.requireNonNull(jobId, "job_id");
        Objects.requireNonNull(fileId, "file_id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "created_at");
        Objects.requireNonNull(metrics, "metrics");
        validateTimestamps(status, startedAt, completedAt, failedAt, errorMessage);
        if (status == JobStatus.COMPLETED && metrics.total() != metrics.completed() + metrics.failed()) {
            throw new IllegalArgumentException(
                    "completed job requires metrics.total == metrics.completed + metrics.failed");
        }
    }

    public record Metrics(int total, int completed, int failed) {
        public Metrics {
            if (total < 0 || completed < 0 || failed < 0) {
                throw new IllegalArgumentException("metrics counts must be non-negative");
            }
            if (completed + failed > total) {
                throw new IllegalArgumentException("metrics.completed + metrics.failed cannot exceed metrics.total");
            }
        }
    }

    public static BatchJobResponse of(
            UUID jobId,
            UUID fileId,
            JobStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant failedAt,
            int total,
            int completed,
            int failed,
            String errorMessage
    ) {
        return new BatchJobResponse(
                jobId,
                fileId,
                status,
                createdAt,
                startedAt,
                completedAt,
                failedAt,
                new Metrics(total, completed, failed),
                errorMessage
        );
    }

    private static void validateTimestamps(
            JobStatus status,
            Instant startedAt,
            Instant completedAt,
            Instant failedAt,
            String errorMessage
    ) {
        switch (status) {
            case QUEUED -> {
                requireNull(startedAt, "started_at");
                requireNull(completedAt, "completed_at");
                requireNull(failedAt, "failed_at");
            }
            case IN_PROGRESS -> {
                requireNonNull(startedAt, "started_at");
                requireNull(completedAt, "completed_at");
                requireNull(failedAt, "failed_at");
            }
            case COMPLETED -> {
                requireNonNull(startedAt, "started_at");
                requireNonNull(completedAt, "completed_at");
                requireNull(failedAt, "failed_at");
            }
            case FAILED -> {
                requireNonNull(failedAt, "failed_at");
                if (errorMessage == null || errorMessage.isBlank()) {
                    throw new IllegalArgumentException("failed job requires error_message");
                }
            }
        }
    }

    private static void requireNonNull(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("status requires " + field);
        }
    }

    private static void requireNull(Instant value, String field) {
        if (value != null) {
            throw new IllegalArgumentException("status must not include " + field);
        }
    }
}
