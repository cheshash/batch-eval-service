package com.batcheval.model;

import com.batcheval.util.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One line in the input JSONL file.
 */
public record BatchInputLine(
        @JsonProperty("request_id") String requestId,
        String model,
        String prompt,
        Map<String, Object> metadata
) {
    public static final int MAX_REQUEST_ID_LENGTH = 128;
    public static final int MAX_MODEL_LENGTH = 128;
    public static final int MAX_PROMPT_LENGTH = 100_000;
    public static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    public static final Pattern MODEL_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    public BatchInputLine {
        requestId = requireNonBlank(requestId, "request_id");
        if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("request_id exceeds " + MAX_REQUEST_ID_LENGTH + " characters");
        }
        if (!REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            throw new IllegalArgumentException("request_id must match [a-zA-Z0-9._-]+");
        }
        model = requireNonBlank(model, "model");
        if (model.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("model exceeds " + MAX_MODEL_LENGTH + " characters");
        }
        if (!MODEL_PATTERN.matcher(model).matches()) {
            throw new IllegalArgumentException("model must match [a-zA-Z0-9._-]+");
        }
        prompt = requireNonBlank(prompt, "prompt");
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt exceeds " + MAX_PROMPT_LENGTH + " characters");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Parse and validate one JSONL line; rejects unknown JSON fields. */
    public static BatchInputLine parseJson(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("blank line");
        }
        try {
            return Json.ingestMapper().readValue(line, BatchInputLine.class);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid JSON: " + ex.getMessage(), ex);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing or empty '" + field + "'");
        }
        return value;
    }
}
