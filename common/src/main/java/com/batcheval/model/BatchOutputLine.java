package com.batcheval.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

/**
 * One line in the result JSONL file written by the worker.
 * Use {@link #success} and {@link #failed} — do not construct invalid combinations directly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchOutputLine(
        @JsonProperty("request_id") String requestId,
        RowStatus status,
        Map<String, Object> response,
        RowError error,
        @JsonProperty("latency_ms") Long latencyMs
) {
    public BatchOutputLine {
        requestId = requireNonBlank(requestId, "request_id");
        Objects.requireNonNull(status, "status");

        switch (status) {
            case SUCCESS -> {
                if (response == null) {
                    throw new IllegalArgumentException("success row requires response");
                }
                if (error != null) {
                    throw new IllegalArgumentException("success row must not include error");
                }
                if (latencyMs == null || latencyMs < 0) {
                    throw new IllegalArgumentException("success row requires non-negative latency_ms");
                }
            }
            case FAILED -> {
                if (error == null) {
                    throw new IllegalArgumentException("failed row requires error");
                }
                if (response != null) {
                    throw new IllegalArgumentException("failed row must not include response");
                }
                if (latencyMs != null) {
                    throw new IllegalArgumentException("failed row must not include latency_ms");
                }
            }
        }
    }

    public static BatchOutputLine success(String requestId, Map<String, Object> response, long latencyMs) {
        return new BatchOutputLine(requestId, RowStatus.SUCCESS, response, null, latencyMs);
    }

    public static BatchOutputLine failed(String requestId, RowError error) {
        return new BatchOutputLine(requestId, RowStatus.FAILED, null, error, null);
    }

    public record RowError(
            RowErrorCode code,
            String message,
            @JsonProperty("http_status") Integer httpStatus,
            Integer attempts
    ) {
        public RowError {
            Objects.requireNonNull(code, "code");
            message = requireNonBlank(message, "message");
            if (attempts != null && attempts < 1) {
                throw new IllegalArgumentException("attempts must be >= 1 when present");
            }
        }

        public static RowError of(RowErrorCode code, String message, Integer httpStatus, Integer attempts) {
            return new RowError(code, message, httpStatus, attempts);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing or empty '" + field + "'");
        }
        return value;
    }
}
